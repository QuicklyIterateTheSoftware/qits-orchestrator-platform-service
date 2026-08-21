package eu.wohlben.qits.orchestrator.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.orchestrator.entity.OpRun;
import eu.wohlben.qits.orchestrator.entity.OpStep;
import eu.wohlben.qits.orchestrator.peer.FakePeers;
import eu.wohlben.qits.orchestrator.persistence.RunStore;
import eu.wohlben.qits.orchestrator.run.RunExecutor;
import eu.wohlben.qits.orchestrator.run.RunStatus;
import eu.wohlben.qits.orchestrator.run.RunTrigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gc process against faked peers: the nine steps, the bodies they send, the summaries they read
 * back, and what a broken pin read does.
 *
 * <p>The peers are {@link FakePeers}, an {@code @Alternative} over {@code PeerClient}'s two call
 * methods — so the urls asserted here are the real ones, resolved from the shipped target
 * configuration, and a wrong path or a wrong peer fails.
 */
@QuarkusTest
class GcProcessTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String USAGE = "/containers/api/gc/usage";
  private static final String DEPLOYMENT_PINS = "/platform-deployments/api/pins";
  private static final String CI_PIN = "/ci/api/daemon";
  private static final String PLAN = "/artifacts/api/gc/plan";
  private static final String SWEEP = "/artifacts/api/gc/sweep";
  private static final String IMAGES = "/containers/api/gc/images";
  private static final String VOLUMES = "/containers/api/gc/volumes";
  private static final String BUILD_CACHE = "/containers/api/gc/build-cache";

  @Inject RunExecutor executor;

  @Inject RunStore runs;

  @Inject FakePeers peers;

  @BeforeEach
  void scriptEveryPeerAsHealthy() {
    peers.reset();
    peers.answer(
        USAGE,
        FakePeers.Scripted.ok(
            """
            {"images":{"count":41,"active":19,"sizeBytes":43500000000,"reclaimableBytes":19300000000},
             "containers":{"count":19,"active":19,"sizeBytes":120000,"reclaimableBytes":0},
             "volumes":{"count":31,"active":22,"sizeBytes":8100000000,"reclaimableBytes":900000000},
             "buildCache":{"count":812,"active":0,"sizeBytes":35100000000,"reclaimableBytes":35100000000}}
            """));
    peers.answer(
        DEPLOYMENT_PINS,
        FakePeers.Scripted.ok(
            """
            {"pins":[{"applicationName":"qits-ci","shas":["aaa111","bbb222"]},
                     {"applicationName":"qits-gateway","shas":["ccc333"]}]}
            """));
    peers.answer(
        CI_PIN,
        FakePeers.Scripted.ok(
            """
            {"daemonName":"qits-ci-daemon","daemonVersion":"2026.815.120000",
             "previousDaemonVersion":"2026.814.101010","source":"pinned"}
            """));
    peers.answer(
        PLAN,
        FakePeers.Scripted.ok(
            """
            {"summary":{"executable":true,"headline":"…","identitiesCondemned":128,
                        "blobsSweepable":91,"reclaimableBytes":19300000000,
                        "reclaimable":"19.3 GB","withheldByGraceWindow":4,"types":[]}}
            """));
    peers.answer(
        SWEEP,
        FakePeers.Scripted.ok(
            """
            {"dryRun":false,"aborted":null,
             "sweep":{"blobsUnlinked":91,"bytesReclaimed":17800000000,"withheldByGraceWindow":4,
                      "withheldBytes":100,"stillReferenced":7,"alreadyGone":0,"unlinkedBlobIds":[]}}
            """));
    peers.answer(
        IMAGES,
        FakePeers.Scripted.ok(
            """
            {"dryRun":false,"examined":73,"bytesReclaimed":9400000000,
             "removed":[{"id":"sha256:1","tags":[],"sizeBytes":1,"reason":"unreferenced"}],
             "kept":[{"id":"sha256:2","tags":["qits/qits-ci:aaa111"],"sizeBytes":2,"reason":"pinned"},
                     {"id":"sha256:3","tags":[],"sizeBytes":3,"reason":"in-use"}],
             "failed":[]}
            """));
    peers.answer(
        VOLUMES,
        FakePeers.Scripted.ok(
            """
            {"dryRun":false,
             "removed":[{"name":"a","reason":"managed-no-row"},{"name":"b","reason":"anonymous"}],
             "kept":[{"name":"c","reason":"unmanaged"}],"failed":[]}
            """));
    peers.answer(
        BUILD_CACHE,
        FakePeers.Scripted.ok(
            """
            {"dryRun":false,"host":{"reclaimedBytes":12600000000,"detail":"…"},
             "builders":[{"container":"buildx_buildkit_x0","reclaimedBytes":3100000000,
                          "detail":"…","error":null}]}
            """));
  }

  /**
   * A poll, the way a deployment does it.
   *
   * <p><b>The clear is load-bearing.</b> A {@code @QuarkusTest} holds ONE request context for the
   * whole test method, so one Hibernate session serves every read here and would answer every poll
   * out of its first-level cache — the run row would stay RUNNING forever while the worker thread
   * closed it in another session. In a deployment each poll is a fresh HTTP request with a session
   * of its own; clearing is how the suite gets the same thing.
   */
  private void nextPoll() {
    runs.getEntityManager().clear();
  }

  private OpRun awaitClosed(UUID id) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      nextPoll();
      OpRun run = runs.run(id).orElseThrow();
      if (!RunStatus.RUNNING.name().equals(run.status)) {
        return run;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("the gc run " + id + " never finished");
  }

  private Map<String, OpStep> stepsOf(UUID id) {
    nextPoll();
    Map<String, OpStep> byId = new LinkedHashMap<>();
    runs.steps(id).forEach(step -> byId.put(step.stepId, step));
    return byId;
  }

  /** An array node's values as strings — Jackson's own stream API is not stable across versions. */
  private static List<String> texts(JsonNode array) {
    List<String> values = new java.util.ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static JsonNode json(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException("not JSON: " + text, e);
    }
  }

  @Test
  void aHealthyPlatformRunsAllNineStepsAndSummarisesEachFromTheAnswer() {
    UUID id = executor.start("gc", RunTrigger.MANUAL, false);
    OpRun run = awaitClosed(id);

    assertEquals(RunStatus.SUCCEEDED.name(), run.status);

    Map<String, OpStep> steps = stepsOf(id);
    assertEquals(
        List.of(
            "usage.before",
            "pins.deployments",
            "pins.ci",
            "artifacts.plan",
            "artifacts.sweep",
            "containers.images",
            "containers.volumes",
            "containers.build-cache",
            "usage.after"),
        runs.steps(id).stream().map(step -> step.stepId).toList());
    steps.values().forEach(step -> assertEquals(RunStatus.SUCCEEDED.name(), step.status, step.stepId));

    // THE SUMMARIES ARE READINGS OF THE ANSWER, never a second computation of it.
    assertEquals(
        "images 43.5 GB (19.3 GB reclaimable), build cache 35.1 GB",
        steps.get("usage.before").summary);
    assertEquals("2 applications pinned", steps.get("pins.deployments").summary);
    assertEquals(
        "qits-ci-daemon 2026.815.120000 (previous 2026.814.101010)", steps.get("pins.ci").summary);
    assertEquals("128 identities, 19.3 GB reclaimable, executable=true", steps.get("artifacts.plan").summary);
    assertEquals("91 blobs unlinked, 17.8 GB reclaimed", steps.get("artifacts.sweep").summary);
    assertEquals("1 images removed, 9.4 GB reclaimed, 2 kept", steps.get("containers.images").summary);
    assertEquals("2 volumes removed, 1 kept", steps.get("containers.volumes").summary);
    assertEquals(
        "host 12.6 GB reclaimed, 1 builder 3.1 GB reclaimed",
        steps.get("containers.build-cache").summary);

    // The url is the shipped target plus the shipped path — a wrong peer would fail here.
    assertEquals(
        "http://qits-platform-deployments:8080/platform-deployments/api/pins",
        steps.get("pins.deployments").requestUrl);
    assertEquals("GET", steps.get("pins.deployments").requestMethod);
    assertEquals(200, steps.get("usage.after").httpStatus);
    // The answer is stored whole, which is what an investigation reads.
    assertTrue(steps.get("containers.images").responseBody.contains("bytesReclaimed"));
  }

  @Test
  void thePinsGoToArtifactsVerbatimUnderTheTwoNamesItReads() {
    UUID id = executor.start("gc", RunTrigger.MANUAL, false);
    awaitClosed(id);

    JsonNode planBody = json(peers.bodiesFor(PLAN).getFirst());
    JsonNode pins = planBody.get("pins");
    assertEquals(
        "qits-ci", pins.get("deployments").get("pins").get(0).get("applicationName").asText());
    assertEquals("2026.815.120000", pins.get("ciDaemon").get("daemonVersion").asText());
    // The sweep is handed the same document, not a re-read of the peers.
    assertEquals(planBody, json(peers.bodiesFor(SWEEP).getFirst()));
  }

  @Test
  void theImageKeepSetIsEveryDeploymentPinAsALocalTagPlusTheConfiguredPrefixes() {
    UUID id = executor.start("gc", RunTrigger.MANUAL, false);
    awaitClosed(id);

    JsonNode body = json(peers.bodiesFor(IMAGES).getFirst());
    assertFalse(body.get("dryRun").asBoolean());
    assertEquals("PT6H", body.get("minAge").asText());
    assertEquals(
        List.of("qits/qits-ci:aaa111", "qits/qits-ci:bbb222", "qits/qits-gateway:ccc333"),
        texts(body.get("keep")));
    assertEquals(
        List.of("qits/build-images/", "qits/graalvmce-musl-builder"),
        texts(body.get("keepPrefixes")));

    JsonNode volumes = json(peers.bodiesFor(VOLUMES).getFirst());
    assertEquals("PT24H", volumes.get("minAge").asText());
    JsonNode cache = json(peers.bodiesFor(BUILD_CACHE).getFirst());
    assertEquals(21474836480L, cache.get("keepStorageBytes").asLong());
  }

  @Test
  void aDryRunCallsEveryPeerWithItsOwnFlagAndSkipsTheSweepThatDeletes() {
    UUID id = executor.start("gc", RunTrigger.MANUAL, true);
    OpRun run = awaitClosed(id);

    // A skipped step does not fail a run.
    assertEquals(RunStatus.SUCCEEDED.name(), run.status);
    assertTrue(run.dryRun);

    Map<String, OpStep> steps = stepsOf(id);
    assertEquals(RunStatus.SKIPPED.name(), steps.get("artifacts.sweep").status);
    assertEquals("dry run", steps.get("artifacts.sweep").error);
    assertNull(steps.get("artifacts.sweep").requestUrl);
    assertTrue(peers.bodiesFor(SWEEP).isEmpty(), "a dry run must not call the sweep");

    // Everything else still runs, and every peer that can plan is told to plan.
    assertEquals(RunStatus.SUCCEEDED.name(), steps.get("artifacts.plan").status);
    assertTrue(json(peers.bodiesFor(IMAGES).getFirst()).get("dryRun").asBoolean());
    assertTrue(json(peers.bodiesFor(VOLUMES).getFirst()).get("dryRun").asBoolean());
    assertTrue(json(peers.bodiesFor(BUILD_CACHE).getFirst()).get("dryRun").asBoolean());
    // usage.after depends on the sweep, and a SKIPPED dependency skips it too — the dry run's
    // "after" figure would be the "before" figure anyway.
    assertEquals(RunStatus.SKIPPED.name(), steps.get("usage.after").status);
  }

  @Test
  void anUnreadablePinSetStopsEverythingThatDeletesOnItAndNothingElse() {
    peers.answer(
        DEPLOYMENT_PINS, FakePeers.Scripted.unreachable("qits-platform-deployments: no route"));

    UUID id = executor.start("gc", RunTrigger.MANUAL, false);
    OpRun run = awaitClosed(id);

    assertEquals(RunStatus.FAILED.name(), run.status);
    Map<String, OpStep> steps = stepsOf(id);
    assertEquals(RunStatus.FAILED.name(), steps.get("pins.deployments").status);
    assertTrue(steps.get("pins.deployments").error.contains("no route"));

    // FAIL-CLOSED: nothing deletes against a keep-set that could not be read.
    assertEquals(RunStatus.SKIPPED.name(), steps.get("artifacts.plan").status);
    assertEquals("skipped: pins.deployments failed", steps.get("artifacts.plan").error);
    assertEquals(RunStatus.SKIPPED.name(), steps.get("artifacts.sweep").status);
    assertEquals("skipped: pins.deployments failed", steps.get("artifacts.sweep").error);
    assertEquals(RunStatus.SKIPPED.name(), steps.get("containers.images").status);
    assertTrue(peers.bodiesFor(PLAN).isEmpty(), "no plan may be asked for without pins");
    assertTrue(peers.bodiesFor(IMAGES).isEmpty(), "no image may be swept without pins");

    // AND EVERYTHING THAT NEEDS NO PINS STILL RUNS. That is the point of the edges being per step:
    // both the volume sweep and the build-cache prune hang off usage.before alone, so a broken pin
    // read costs the platform no reclaim it could have had. The build cache is the larger half of
    // the measured problem, and it has no keep-set to be wrong about.
    assertEquals(RunStatus.SUCCEEDED.name(), steps.get("usage.before").status);
    assertEquals(RunStatus.SUCCEEDED.name(), steps.get("containers.volumes").status);
    assertEquals(RunStatus.SUCCEEDED.name(), steps.get("containers.build-cache").status);
    assertFalse(peers.bodiesFor(BUILD_CACHE).isEmpty(), "the prune needs no pins and must be asked for");

    // usage.after is the one that still goes: it depends on all four deleting steps, and two of
    // them were skipped, so an "after" figure would be measuring a run that did not happen.
    assertEquals(RunStatus.SKIPPED.name(), steps.get("usage.after").status);
  }

  @Test
  void aPeerThatRefusesTheCallIsAFailedStepNamingTheStatus() {
    peers.answer(PLAN, FakePeers.Scripted.status(401, "{\"message\":\"unauthorized\"}"));

    UUID id = executor.start("gc", RunTrigger.MANUAL, false);
    OpRun run = awaitClosed(id);

    assertEquals(RunStatus.FAILED.name(), run.status);
    Map<String, OpStep> steps = stepsOf(id);
    assertEquals(RunStatus.FAILED.name(), steps.get("artifacts.plan").status);
    assertEquals(401, steps.get("artifacts.plan").httpStatus);
    assertTrue(steps.get("artifacts.plan").error.endsWith("answered 401"), steps.get("artifacts.plan").error);
    // The refusal's body is kept, because it usually says which audience was expected.
    assertTrue(steps.get("artifacts.plan").responseBody.contains("unauthorized"));
    assertEquals(RunStatus.SKIPPED.name(), steps.get("artifacts.sweep").status);
    assertEquals("skipped: artifacts.plan failed", steps.get("artifacts.sweep").error);
  }
}
