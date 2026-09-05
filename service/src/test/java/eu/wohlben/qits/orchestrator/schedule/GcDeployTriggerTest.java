package eu.wohlben.qits.orchestrator.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.orchestrator.entity.OpRun;
import eu.wohlben.qits.orchestrator.peer.FakePeers;
import eu.wohlben.qits.orchestrator.persistence.RunStore;
import eu.wohlben.qits.orchestrator.process.gc.GcConfig;
import eu.wohlben.qits.orchestrator.process.gc.GcProcess;
import eu.wohlben.qits.orchestrator.run.RunExecutor;
import eu.wohlben.qits.orchestrator.run.RunStatus;
import eu.wohlben.qits.orchestrator.run.RunTrigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trailing-edge debounce: a deployment wave becomes one run, and the two ways it declines to
 * start one.
 *
 * <p>Nothing here waits for a clock. {@link ManualDeployTriggerTimer} is an {@code @Alternative}
 * over the seam the trigger schedules through, so "the quiet period lapsed" is a method call — see
 * that class for why a real delay would make every assertion below a race.
 *
 * <p>The runs that do start are real runs through the real executor: the peers are faked and nothing
 * is scripted, so every step fails in microseconds and the run closes. What is under test is which
 * runs exist and what their trigger says, never what a step answered.
 */
@QuarkusTest
class GcDeployTriggerTest {

  @Inject GcDeployTrigger trigger;

  @Inject ManualDeployTriggerTimer timer;

  @Inject GcConfig config;

  @Inject RunExecutor executor;

  @Inject RunStore runs;

  @Inject FakePeers peers;

  @BeforeEach
  void quiet() {
    peers.reset();
    timer.reset();
    awaitNoActiveRun();
  }

  /** One deployment, as the listener hands it over. */
  private void deployment(String application) {
    trigger.onDeploymentActive(application, "2026.905.62255", "dev");
  }

  /**
   * A fresh read of the run log.
   *
   * <p>The clear is load-bearing for the same reason {@code RunExecutorTest.nextPoll} clears: a
   * {@code @QuarkusTest} holds ONE request context for the whole method, so one Hibernate session
   * would answer every read out of its first-level cache while the worker thread wrote in another.
   */
  private List<OpRun> gcRuns() {
    runs.getEntityManager().clear();
    return runs.runs(GcProcess.KIND, 50);
  }

  private Set<UUID> gcRunIds() {
    return gcRuns().stream().map(run -> run.id).collect(Collectors.toSet());
  }

  /** The runs this test started, as opposed to any a neighbouring class left in the shared store. */
  private List<OpRun> newSince(Set<UUID> before) {
    return gcRuns().stream().filter(run -> !before.contains(run.id)).toList();
  }

  private void awaitNoActiveRun() {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      runs.getEntityManager().clear();
      if (runs.active(GcProcess.KIND).isEmpty()) {
        return;
      }
      sleep();
    }
    throw new AssertionError("a gc run never finished");
  }

  private void awaitClosed(UUID id) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      runs.getEntityManager().clear();
      OpRun run = runs.run(id).orElseThrow();
      if (!RunStatus.RUNNING.name().equals(run.status)) {
        return;
      }
      sleep();
    }
    throw new AssertionError("run " + id + " never finished");
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The shipped defaults are what a deployment gets with nothing configured. */
  @Test
  void theTriggerIsOnAndTenMinutesOutOfTheBox() {
    assertTrue(config.deployTriggerEnabled());
    assertEquals(Duration.ofMinutes(10), config.deployTriggerQuietPeriod());
  }

  @Test
  void aWaveOfDeploymentsCollapsesIntoOneRun() {
    Set<UUID> before = gcRunIds();

    deployment("qits-artifacts");
    deployment("qits-containers");
    deployment("qits-projects");
    deployment("qits-workspaces");
    deployment("qits-ci");

    // Five events, five arms — and four of them cancelled the one before, so exactly one run is
    // parked. That is the whole of the debounce.
    assertEquals(5, timer.armedDelays().size());
    assertEquals(4, timer.cancellations());
    assertTrue(timer.isArmed());
    // Nothing has run yet: a wave that is still arriving must not be planned against.
    assertEquals(List.of(), newSince(before));

    timer.elapse();

    List<OpRun> started = newSince(before);
    assertEquals(1, started.size(), "a wave of five deployments started " + started.size() + " runs");
    OpRun run = started.get(0);
    assertEquals(RunTrigger.EVENT.wireName(), run.trigger);
    // NEVER a dry run: this trigger exists to reclaim.
    assertFalse(run.dryRun);
    assertFalse(timer.isArmed());

    awaitClosed(run.id);
  }

  /** Every event moves the deadline, and it moves it by the configured quiet period. */
  @Test
  void aLateEventPushesThePendingRunBackRatherThanAddingASecond() {
    deployment("qits-artifacts");
    assertEquals(1, timer.armedDelays().size());
    assertEquals(0, timer.cancellations());

    deployment("qits-containers");

    assertEquals(2, timer.armedDelays().size());
    assertEquals(1, timer.cancellations(), "the first event's run was not cancelled");
    assertTrue(timer.isArmed());
    assertEquals(
        List.of(config.deployTriggerQuietPeriod(), config.deployTriggerQuietPeriod()),
        timer.armedDelays());
  }

  /**
   * The executor's own single-flight rule is the one that decides, and a refusal re-arms the wave.
   *
   * <p>{@code RunStore.open} refuses a second run of a kind inside its own opening transaction, so
   * this test holds a run RUNNING (the peers block) and lets the quiet period lapse underneath it.
   */
  @Test
  void aBusyExecutorRearmsTheWaveInsteadOfLosingIt() {
    Set<UUID> before = gcRunIds();
    peers.hold();
    UUID active = executor.start(GcProcess.KIND, RunTrigger.MANUAL, false);
    try {
      deployment("qits-artifacts");
      timer.elapse();

      // No second run — and the wave is parked again rather than dropped, because the identities
      // that caused it may be ones the run in flight had already planned around.
      assertEquals(
          List.of(active),
          newSince(before).stream().map(run -> run.id).toList(),
          "a second gc run was opened while one was active");
      assertTrue(timer.isArmed(), "the refused wave was not re-armed");
      assertEquals(2, timer.armedDelays().size());
    } finally {
      peers.release();
    }
    awaitClosed(active);

    // …and when it does lapse against a free executor, the wave finally gets its run.
    timer.elapse();
    List<OpRun> started =
        newSince(before).stream().filter(run -> !run.id.equals(active)).toList();
    assertEquals(1, started.size());
    assertEquals(RunTrigger.EVENT.wireName(), started.get(0).trigger);
    awaitClosed(started.get(0).id);
  }
}
