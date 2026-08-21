package eu.wohlben.qits.orchestrator.process.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.orchestrator.peer.PeerTarget;
import eu.wohlben.qits.orchestrator.process.RunContext;
import eu.wohlben.qits.orchestrator.process.StepDefinition;
import eu.wohlben.qits.orchestrator.process.StepResult;
import eu.wohlben.qits.orchestrator.process.TechnicalProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The gc process: the platform's one deletion run, across every store that grows.
 *
 * <h2>Who deletes what</h2>
 *
 * Nothing here. Registry blobs, tags and manifests are qits-artifacts' own GC engine; host images,
 * orphan volumes and buildkit cache are qits-containers', which is the component that holds the
 * platform's docker socket. This process reads the pin set, hands it to the deleters and records
 * what each one said.
 *
 * <h2>The pins, and why they are read here</h2>
 *
 * <p>A pin is something that must survive a collection: an image sha a restart or a rollback would
 * pull ({@code pins.deployments}) and the ci daemon binary a run would launch ({@code pins.ci}).
 * They are read ONCE per run and handed to every deleter, so every deleter judges against one
 * answer taken at one moment.
 *
 * <p><b>They are read here because this is the one component that holds an idp client for every
 * peer.</b> qits-artifacts' own HTTP pin readers stay as its no-body fallback, and they are exactly
 * what was 401-ing: a credential-less reader of a guarded peer. A supplied pin set moves that read
 * to the caller that can authenticate.
 *
 * <p><b>Fail-closed, and the edges are what makes it so.</b> A failed pin read skips every step that
 * would delete on the strength of that pin — the artifacts plan and sweep, and the image sweep —
 * while the volume sweep and the build-cache prune, which need no pins, still run. Nothing deletes
 * against a keep-set it could not read, and nothing that needs no keep-set is stopped by one.
 *
 * <h2>The steps</h2>
 *
 * <pre>
 * usage.before        containers   GET  /containers/api/gc/usage
 * pins.deployments    deployments  GET  /platform-deployments/api/pins
 * pins.ci             ci           GET  /ci/api/daemon
 * artifacts.plan      artifacts    POST /artifacts/api/gc/plan {pins}      ← pins.*
 * artifacts.sweep     artifacts    POST /artifacts/api/gc/sweep {pins}     ← artifacts.plan
 * containers.images   containers   POST /containers/api/gc/images          ← pins.deployments
 * containers.volumes  containers   POST /containers/api/gc/volumes         ← usage.before
 * containers.build-cache containers POST /containers/api/gc/build-cache    ← usage.before
 * usage.after         containers   GET  /containers/api/gc/usage           ← everything that deletes
 * </pre>
 *
 * <p>{@code usage.before} and {@code usage.after} are the same call twice, and the pair is the
 * point: the run's own measurement of what it achieved, taken by the component that owns the store
 * rather than added up from what each step claimed.
 */
@ApplicationScoped
public class GcProcess implements TechnicalProcess {

  /** The kind, in every url and in every stored row. */
  public static final String KIND = "gc";

  static final String USAGE_BEFORE = "usage.before";
  static final String PINS_DEPLOYMENTS = "pins.deployments";
  static final String PINS_CI = "pins.ci";
  static final String ARTIFACTS_PLAN = "artifacts.plan";
  static final String ARTIFACTS_SWEEP = "artifacts.sweep";
  static final String CONTAINERS_IMAGES = "containers.images";
  static final String CONTAINERS_VOLUMES = "containers.volumes";
  static final String CONTAINERS_BUILD_CACHE = "containers.build-cache";
  static final String USAGE_AFTER = "usage.after";

  private static final String USAGE_PATH = "/containers/api/gc/usage";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject GcConfig config;

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String name() {
    return "Garbage collection";
  }

  @Override
  public String description() {
    return "Reads the platform's pin set once, then asks every store's own owner to delete what"
        + " nothing pins: registry identities and blobs (qits-artifacts), host images, orphan"
        + " volumes and buildkit cache (qits-containers). Measures disk before and after.";
  }

  @Override
  public List<StepDefinition> steps() {
    return List.of(
        new StepDefinition(
            USAGE_BEFORE,
            "Disk usage before",
            PeerTarget.CONTAINERS,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.CONTAINERS, USAGE_PATH),
                    answer -> GcSummaries.usage(answer.json()))),
        new StepDefinition(
            PINS_DEPLOYMENTS,
            "Deployment pins",
            PeerTarget.DEPLOYMENTS,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.DEPLOYMENTS, "/platform-deployments/api/pins"),
                    answer -> GcSummaries.deploymentPins(answer.json()))),
        new StepDefinition(
            PINS_CI,
            "CI daemon pin",
            PeerTarget.CI,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.CI, "/ci/api/daemon"),
                    answer -> GcSummaries.ciPin(answer.json()))),
        new StepDefinition(
            ARTIFACTS_PLAN,
            "Plan the registry collection",
            PeerTarget.ARTIFACTS,
            List.of(PINS_DEPLOYMENTS, PINS_CI),
            context ->
                StepResult.of(
                    context
                        .peers()
                        .post(PeerTarget.ARTIFACTS, "/artifacts/api/gc/plan", pinsBody(context)),
                    answer -> GcSummaries.artifactsPlan(answer.json()))),
        new StepDefinition(
            ARTIFACTS_SWEEP,
            "Sweep the registry",
            PeerTarget.ARTIFACTS,
            List.of(ARTIFACTS_PLAN),
            context ->
                context.dryRun()
                    ? StepResult.skipped("dry run")
                    : StepResult.of(
                        context
                            .peers()
                            .post(
                                PeerTarget.ARTIFACTS, "/artifacts/api/gc/sweep", pinsBody(context)),
                        answer -> GcSummaries.artifactsSweep(answer.json()))),
        new StepDefinition(
            CONTAINERS_IMAGES,
            "Collect host images",
            PeerTarget.CONTAINERS,
            List.of(PINS_DEPLOYMENTS),
            context ->
                StepResult.of(
                    context
                        .peers()
                        .post(PeerTarget.CONTAINERS, "/containers/api/gc/images", imagesBody(context)),
                    answer -> GcSummaries.images(answer.json()))),
        new StepDefinition(
            CONTAINERS_VOLUMES,
            "Collect orphan volumes",
            PeerTarget.CONTAINERS,
            List.of(USAGE_BEFORE),
            context ->
                StepResult.of(
                    context
                        .peers()
                        .post(
                            PeerTarget.CONTAINERS,
                            "/containers/api/gc/volumes",
                            volumesBody(context)),
                    answer -> GcSummaries.volumes(answer.json()))),
        new StepDefinition(
            CONTAINERS_BUILD_CACHE,
            "Prune the build cache",
            PeerTarget.CONTAINERS,
            // usage.before, NOT containers.images. A prune needs no pin set of its own, so hanging
            // it off the image sweep would have made a broken pin read cost the platform tens of
            // gigabytes of cache reclaim for no reason. Declaration order still puts it after the
            // image sweep, which is all the ordering it ever wanted.
            List.of(USAGE_BEFORE),
            context ->
                StepResult.of(
                    context
                        .peers()
                        .post(
                            PeerTarget.CONTAINERS,
                            "/containers/api/gc/build-cache",
                            buildCacheBody(context)),
                    answer -> GcSummaries.buildCache(answer.json()))),
        new StepDefinition(
            USAGE_AFTER,
            "Disk usage after",
            PeerTarget.CONTAINERS,
            List.of(ARTIFACTS_SWEEP, CONTAINERS_IMAGES, CONTAINERS_VOLUMES, CONTAINERS_BUILD_CACHE),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.CONTAINERS, USAGE_PATH),
                    answer -> GcSummaries.usage(answer.json()))));
  }

  /**
   * The pin set, as qits-artifacts takes it:
   *
   * <pre>
   * {"pins": {"deployments": &lt;the deployments answer, verbatim&gt;,
   *           "ciDaemon":    &lt;the ci answer, verbatim&gt;}}
   * </pre>
   *
   * <p><b>Verbatim means the bytes the peer sent.</b> Each member is the other service's whole
   * response document, re-embedded rather than re-shaped: the two contracts belong to
   * qits-platform-deployments and qits-ci, and a projection here would be a third copy of them to
   * keep in step.
   *
   * <p><b>A member this run could not read is ABSENT, not null.</b> qits-artifacts reads a missing
   * member as that pin source being unanswered, which makes the plan not executable and aborts a
   * sweep — the fail-closed rule, unchanged. In practice the edges mean this step never runs
   * without both, so the absent case is the belt.
   */
  private static String pinsBody(RunContext context) {
    ObjectNode pins = JSON.createObjectNode();
    context.answer(PINS_DEPLOYMENTS).ifPresent(node -> pins.set("deployments", node));
    context.answer(PINS_CI).ifPresent(node -> pins.set("ciDaemon", node));
    ObjectNode body = JSON.createObjectNode();
    body.set("pins", pins);
    return body.toString();
  }

  /**
   * The host image request:
   *
   * <pre>{@code {"dryRun":…, "minAge":"PT6H", "keep":[…], "keepPrefixes":[…]}}</pre>
   *
   * <p><b>The keep-set is the deployments pin answer, translated into local tags.</b> A deployment
   * pins {@code (applicationName, shas)}; the tag the host carries for one is
   * {@code qits/<applicationName>:<sha>} — the spelling {@code .config/qits/ci-post-receive.yml}
   * pushes and the deployer pulls. The prefixes beside it are configuration: build images and the
   * native toolchain image, which no deployment pins and an age rule condemns first.
   *
   * <p>A pin answer this run could not read leaves {@code keep} EMPTY — but this step never runs in
   * that case, because {@code pins.deployments} is its declared dependency and a failed dependency
   * skips it. The two together are why an empty keep-set can never reach qits-containers.
   */
  private String imagesBody(RunContext context) {
    ArrayNode keep = JSON.createArrayNode();
    context
        .answer(PINS_DEPLOYMENTS)
        .ifPresent(
            answer -> {
              for (JsonNode pin : answer.path("pins")) {
                String application = pin.path("applicationName").asText(null);
                if (application == null || application.isBlank()) {
                  continue;
                }
                for (JsonNode sha : pin.path("shas")) {
                  String value = sha.asText(null);
                  if (value != null && !value.isBlank()) {
                    keep.add("qits/" + application + ":" + value);
                  }
                }
              }
            });

    ObjectNode body = JSON.createObjectNode();
    body.put("dryRun", context.dryRun());
    body.put("minAge", config.imageMinAge().toString());
    body.set("keep", keep);
    ArrayNode prefixes = JSON.createArrayNode();
    config.imageKeepPrefixes().forEach(prefixes::add);
    body.set("keepPrefixes", prefixes);
    return body.toString();
  }

  /** {@code {"dryRun":…, "minAge":"PT24H"}} */
  private String volumesBody(RunContext context) {
    ObjectNode body = JSON.createObjectNode();
    body.put("dryRun", context.dryRun());
    body.put("minAge", config.volumeMinAge().toString());
    return body.toString();
  }

  /** {@code {"dryRun":…, "keepStorageBytes":21474836480}} */
  private String buildCacheBody(RunContext context) {
    ObjectNode body = JSON.createObjectNode();
    body.put("dryRun", context.dryRun());
    body.put("keepStorageBytes", config.buildCacheKeepBytes());
    return body.toString();
  }
}
