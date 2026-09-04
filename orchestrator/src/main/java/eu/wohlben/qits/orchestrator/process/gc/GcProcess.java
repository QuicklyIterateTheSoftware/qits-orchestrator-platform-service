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
 * pull ({@code pins.deployments}), the ci daemon binary a run would launch ({@code pins.ci}), a
 * version a repository's main still references in a manifest ({@code pins.dependencies}) and a
 * container image a workspace, editor or agent launch would pull ({@code pins.images}). They are
 * read ONCE per run and handed to every deleter, so every deleter judges against one answer taken
 * at one moment.
 *
 * <p><b>The last two are CONSUMPTION rather than deployment</b>, which is why neither of the first
 * two could stand in for them: nothing deploys a library a pom pins, and nothing deploys the
 * workspace image a person's next click pulls. A release-age rule cannot see either, so before
 * these two steps existed the only thing keeping them alive was how recently somebody happened to
 * ask for them.
 *
 * <p><b>They are read here because this is the one component that holds an idp client for every
 * peer.</b> qits-artifacts' own HTTP pin readers stay as its no-body fallback, and they are exactly
 * what was 401-ing: a credential-less reader of a guarded peer. A supplied pin set moves that read
 * to the caller that can authenticate.
 *
 * <p><b>Fail-closed, and the edges are what makes it so.</b> A failed pin read skips every step that
 * would delete on the strength of that pin — the artifacts plan and sweep depend on all FOUR pin
 * reads, the image sweep on the deployments one — while the volume sweep and the build-cache prune,
 * which need no pins, still run. Nothing deletes against a keep-set it could not read, and nothing
 * that needs no keep-set is stopped by one. A pin source added to the body is a pin source added to
 * those two dependency lists in the same commit, or the registry would collect against a keep-set
 * missing whatever that source protects.
 *
 * <h2>The steps</h2>
 *
 * <pre>
 * usage.before           containers     GET  /containers/api/gc/usage
 * artifacts.usage.before artifacts      GET  /artifacts/api/store/summary
 * pins.deployments       deployments    GET  /platform-deployments/api/pins
 * pins.ci                ci             GET  /ci/api/daemon
 * pins.dependencies      maintenance    GET  /maintenance/api/pins
 * pins.images            configuration  GET  /configuration/api/pins
 * artifacts.plan         artifacts      POST /artifacts/api/gc/plan {pins}   ← pins.*
 * artifacts.sweep        artifacts      POST /artifacts/api/gc/sweep {pins}  ← artifacts.plan, pins.*
 * containers.images      containers     POST /containers/api/gc/images       ← pins.deployments
 * containers.volumes     containers     POST /containers/api/gc/volumes      ← usage.before
 * containers.build-cache containers     POST /containers/api/gc/build-cache  ← usage.before
 * repos.catalogue        projects       GET  /projects/api/repositories
 * branches.sweep         workspaces     POST /workspaces/api/gc/branches     ← repos.catalogue
 * artifacts.usage.after  artifacts      GET  /artifacts/api/store/summary    ← artifacts.sweep
 * usage.after            containers     GET  /containers/api/gc/usage        ← everything that frees disk
 * </pre>

 * <p><b>The branch sweep is the same pin pattern, one store further out.</b> The repository
 * catalogue is its keep-set's complement — the iteration set — read here for the same reason the
 * pins are, and handed to qits-workspaces, which owns branch semantics: what a branch's parent is,
 * whether a workspace stands on it, and the single cleanup criterion every UI click already goes
 * through. A failed catalogue read skips the sweep (nothing deletes over a list it could not
 * read); {@code usage.after} does not wait for it, because deleted refs free no docker disk.
 *
 * <p>{@code usage.before} and {@code usage.after} are the same call twice, and the pair is the
 * point: the run's own measurement of what it achieved, taken by the component that owns the store
 * rather than added up from what each step claimed.
 *
 * <p><b>{@code artifacts.usage.*} is the second plane of the same measurement, and it exists
 * because the first one cannot see it.</b> The registry's bytes are rows in qits-artifacts' postgres
 * and files behind them — invisible to the docker read, which knows only the host's images,
 * containers, volumes and cache. A run whose whole receipt was {@code docker system df} therefore
 * reported a platform that was not growing while the registry did: the 2026-09-04 storage incident
 * was 50 GB nobody's receipt showed. Its after-step hangs off the registry sweep alone rather than
 * off everything that frees disk, so a broken container prune still leaves the registry's own
 * before-and-after in the run.
 */
@ApplicationScoped
public class GcProcess implements TechnicalProcess {

  /** The kind, in every url and in every stored row. */
  public static final String KIND = "gc";

  static final String USAGE_BEFORE = "usage.before";
  static final String ARTIFACTS_USAGE_BEFORE = "artifacts.usage.before";
  static final String PINS_DEPLOYMENTS = "pins.deployments";
  static final String PINS_CI = "pins.ci";
  static final String PINS_DEPENDENCIES = "pins.dependencies";
  static final String PINS_IMAGES = "pins.images";
  static final String ARTIFACTS_PLAN = "artifacts.plan";
  static final String ARTIFACTS_SWEEP = "artifacts.sweep";
  static final String CONTAINERS_IMAGES = "containers.images";
  static final String CONTAINERS_VOLUMES = "containers.volumes";
  static final String CONTAINERS_BUILD_CACHE = "containers.build-cache";
  static final String REPOS_CATALOGUE = "repos.catalogue";
  static final String BRANCHES_SWEEP = "branches.sweep";
  static final String ARTIFACTS_USAGE_AFTER = "artifacts.usage.after";
  static final String USAGE_AFTER = "usage.after";

  private static final String USAGE_PATH = "/containers/api/gc/usage";

  private static final String STORE_PATH = "/artifacts/api/store/summary";

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
        + " volumes and buildkit cache (qits-containers). Measures host disk and the registry"
        + " store before and after.";
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
            ARTIFACTS_USAGE_BEFORE,
            "Registry store before",
            PeerTarget.ARTIFACTS,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.ARTIFACTS, STORE_PATH),
                    answer -> GcSummaries.storeUsage(answer.json()))),
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
            PINS_DEPENDENCIES,
            "Dependency pins",
            PeerTarget.MAINTENANCE,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.MAINTENANCE, "/maintenance/api/pins"),
                    answer -> GcSummaries.dependencyPins(answer.json()))),
        new StepDefinition(
            PINS_IMAGES,
            "Configured image pins",
            PeerTarget.CONFIGURATION,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.CONFIGURATION, "/configuration/api/pins"),
                    answer -> GcSummaries.imagePins(answer.json()))),
        new StepDefinition(
            ARTIFACTS_PLAN,
            "Plan the registry collection",
            PeerTarget.ARTIFACTS,
            List.of(PINS_DEPLOYMENTS, PINS_CI, PINS_DEPENDENCIES, PINS_IMAGES),
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
            // Every pin read as well as the plan, because this is the step that DELETES and its body
            // is its own pinsBody rather than the plan's. The plan edge already carries all four
            // transitively; naming them here is the doctrine rather than the mechanism — a step that
            // deletes on the strength of a pin carries that pin's edge, so a source added to the
            // body is a source that cannot be added without this list noticing.
            List.of(ARTIFACTS_PLAN, PINS_DEPLOYMENTS, PINS_CI, PINS_DEPENDENCIES, PINS_IMAGES),
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
            REPOS_CATALOGUE,
            "Repository catalogue",
            PeerTarget.PROJECTS,
            List.of(),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.PROJECTS, "/projects/api/repositories"),
                    answer -> GcSummaries.repositoryCatalogue(answer.json()))),
        new StepDefinition(
            BRANCHES_SWEEP,
            "Sweep merged branches",
            PeerTarget.WORKSPACES,
            List.of(REPOS_CATALOGUE),
            context ->
                StepResult.of(
                    context
                        .peers()
                        .post(
                            PeerTarget.WORKSPACES,
                            "/workspaces/api/gc/branches",
                            branchesBody(context)),
                    answer -> GcSummaries.branchesSweep(answer.json()))),
        new StepDefinition(
            ARTIFACTS_USAGE_AFTER,
            "Registry store after",
            PeerTarget.ARTIFACTS,
            // The registry sweep alone. The docker measurement below waits on everything that frees
            // host disk; the registry's own bytes are freed by exactly one step, so a container
            // prune that failed must not cost the run the one figure that would have shown the
            // registry shrinking.
            List.of(ARTIFACTS_SWEEP),
            context ->
                StepResult.of(
                    context.peers().get(PeerTarget.ARTIFACTS, STORE_PATH),
                    answer -> GcSummaries.storeUsage(answer.json()))),
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
   * {"pins": {"deployments":     &lt;the deployments answer, verbatim&gt;,
   *           "ciDaemon":        &lt;the ci answer, verbatim&gt;,
   *           "dependencies":    &lt;the maintenance answer, verbatim&gt;,
   *           "configuredImages":&lt;the configuration answer, verbatim&gt;}}
   * </pre>
   *
   * <p><b>Verbatim means the bytes the peer sent.</b> Each member is the other service's whole
   * response document, re-embedded rather than re-shaped: the four contracts belong to
   * qits-platform-deployments, qits-ci, qits-platform-maintenance and qits-configuration, and a
   * projection here would be a fifth copy of them to keep in step.
   *
   * <p><b>A member this run could not read is ABSENT, not null.</b> qits-artifacts reads a missing
   * member as that pin source being unanswered, which makes the plan not executable and aborts a
   * sweep — the fail-closed rule, unchanged. In practice the edges mean this step never runs
   * without all four, so the absent case is the belt.
   */
  private static String pinsBody(RunContext context) {
    ObjectNode pins = JSON.createObjectNode();
    context.answer(PINS_DEPLOYMENTS).ifPresent(node -> pins.set("deployments", node));
    context.answer(PINS_CI).ifPresent(node -> pins.set("ciDaemon", node));
    context.answer(PINS_DEPENDENCIES).ifPresent(node -> pins.set("dependencies", node));
    context.answer(PINS_IMAGES).ifPresent(node -> pins.set("configuredImages", node));
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

  /**
   * The branch sweep request:
   *
   * <pre>{@code {"dryRun":…, "repositories":[{"id","name","mainBranch"}…], "keepPrefixes":[…]}}</pre>
   *
   * <p><b>A projection this time, not the verbatim document</b> — the request shape belongs to
   * qits-workspaces' own contract ({@code SweepRepository}), so the catalogue rows are respelled
   * into it rather than re-embedded; the three fields are exactly what the sweep judges by. Unlike
   * the artifacts sweep, this one runs on a dry run too: qits-workspaces judges identically and
   * deletes nothing, so the nightly dry figures are real figures.
   */
  private String branchesBody(RunContext context) {
    ArrayNode repositories = JSON.createArrayNode();
    context
        .answer(REPOS_CATALOGUE)
        .ifPresent(
            answer -> {
              for (JsonNode row : answer.path("repositories")) {
                String id = row.path("id").asText(null);
                if (id == null || id.isBlank()) {
                  continue;
                }
                ObjectNode repository = repositories.addObject();
                repository.put("id", id);
                repository.put("name", row.path("name").asText(""));
                repository.put("mainBranch", row.path("mainBranch").asText(""));
              }
            });
    ObjectNode body = JSON.createObjectNode();
    body.put("dryRun", context.dryRun());
    body.set("repositories", repositories);
    ArrayNode prefixes = JSON.createArrayNode();
    config.branchKeepPrefixes().forEach(prefixes::add);
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

  /**
   * {@code {"dryRun":…, "keepStorageBytes":…, "builderKeepStorageBytes":…}}
   *
   * <p><b>Two budgets, because the two caches are not the same kind of thing.</b>
   * {@code keepStorageBytes} is the host builder's — the cache every CI build warms and re-reads.
   * {@code builderKeepStorageBytes} is a {@code buildx_buildkit_*} container's, which is warmed once
   * while a machine is bootstrapped and then unread until the next bootstrap.
   *
   * <p>One number for both is what left a 13.7 GB bootstrap builder untouched every night: it was
   * smaller than the host budget, so a shared keep-storage never reached it. qits-containers falls
   * back to {@code keepStorageBytes} when the second field is absent, so sending it is safe against
   * a peer that predates it.
   */
  private String buildCacheBody(RunContext context) {
    ObjectNode body = JSON.createObjectNode();
    body.put("dryRun", context.dryRun());
    body.put("keepStorageBytes", config.buildCacheKeepBytes());
    body.put("builderKeepStorageBytes", config.builderCacheKeepBytes());
    return body.toString();
  }
}
