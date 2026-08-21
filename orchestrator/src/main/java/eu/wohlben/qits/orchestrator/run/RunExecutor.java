package eu.wohlben.qits.orchestrator.run;

import eu.wohlben.qits.orchestrator.error.NoSuchProcessException;
import eu.wohlben.qits.orchestrator.peer.PeerClient;
import eu.wohlben.qits.orchestrator.persistence.RunStore;
import eu.wohlben.qits.orchestrator.process.ProcessRegistry;
import eu.wohlben.qits.orchestrator.process.RunContext;
import eu.wohlben.qits.orchestrator.process.StepDefinition;
import eu.wohlben.qits.orchestrator.process.StepResult;
import eu.wohlben.qits.orchestrator.process.TechnicalProcess;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Runs one technical process, start to finish, on a thread of its own.
 *
 * <p><b>ONE THREAD FOR THE WHOLE SERVICE.</b> Not one per kind: a run's work is other people's
 * disks, and two runs overlapping would be two plans against two moments of the same stores. One
 * thread also makes the run log a sequence rather than an interleaving, which is what a person
 * reading it expects. A second run of the SAME kind is refused earlier, by {@link RunStore#open}.
 *
 * <p><b>The route does not wait.</b> {@code POST /processes/{kind}/runs} answers 202 with the id as
 * soon as the rows exist; a gc run is minutes of somebody else's pruning and an HTTP request is the
 * wrong place to hold it. The UI polls.
 *
 * <h2>What a failure does</h2>
 *
 * Steps run in DECLARATION ORDER. Before each one, its dependencies are looked at:
 *
 * <ul>
 *   <li>all SUCCEEDED — the step runs;
 *   <li>any FAILED or SKIPPED — the step is SKIPPED with {@code error = "skipped: <step> failed"},
 *       naming the step that actually FAILED rather than the skipped one in between. A cascade that
 *       named its neighbour would make a reader walk the graph backwards to find the cause.
 * </ul>
 *
 * <b>Independent steps still run.</b> A failed pin read stops everything that deletes on the
 * strength of those pins and nothing else — the volume sweep and the build-cache prune do not need
 * a pin set, and a night where they are skipped too is a night of no reclaim for no reason. That is
 * the whole point of the edges being declared per step.
 *
 * <p><b>The run is FAILED if any step FAILED.</b> A SKIPPED step does not fail it: a skip is the
 * consequence of a failure already counted.
 */
@ApplicationScoped
public class RunExecutor {

  private static final Logger LOG = Logger.getLogger(RunExecutor.class);

  @Inject ProcessRegistry registry;

  @Inject RunStore runs;

  @Inject PeerClient peers;

  /**
   * The one worker. A daemon thread with a name, so a stuck run is identifiable in a thread dump
   * and a JVM shutting down is not held open by a prune somebody else is still doing.
   */
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "qits-orchestrator-run");
            thread.setDaemon(true);
            return thread;
          });

  void onShutdown(@Observes ShutdownEvent event) {
    worker.shutdown();
    try {
      // Long enough for a step in flight to record its answer, short enough that a redeploy is not
      // held up by a peer that will never reply — its call has its own timeout anyway.
      if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
        worker.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      worker.shutdownNow();
    }
  }

  /**
   * Opens a run and queues it.
   *
   * @return the run id, which the route answers with a 202
   * @throws NoSuchProcessException the kind is not registered — a 404
   * @throws eu.wohlben.qits.orchestrator.error.RunAlreadyActiveException one is already going — 409
   */
  public UUID start(String kind, RunTrigger trigger, boolean dryRun) {
    TechnicalProcess process =
        registry.byKind(kind).orElseThrow(() -> new NoSuchProcessException(kind));
    UUID runId = runs.open(kind, trigger, dryRun, process.steps(), Instant.now());
    worker.execute(() -> execute(process, runId, dryRun));
    return runId;
  }

  /**
   * Runs one process to the end. It never throws: a run that blew up is a FAILED run with the
   * sentence in its summary, because a thrown exception on the worker would leave a RUNNING row
   * nothing can ever close and no second run of that kind would be allowed again.
   */
  void execute(TechnicalProcess process, UUID runId, boolean dryRun) {
    RunContext context = new RunContext(runId, dryRun, peers);
    Map<String, RunStatus> statuses = new LinkedHashMap<>();
    // Which FAILED step is the reason a given step was skipped. A cascade carries the origin
    // forward, so the third step in a chain still names the one that broke.
    Map<String, String> failureOrigin = new HashMap<>();
    List<String> lines = new ArrayList<>();
    boolean anyFailed = false;

    try {
      for (StepDefinition step : process.steps()) {
        String blocker = blocker(step, statuses, failureOrigin);
        StepResult result;
        if (blocker != null) {
          result = StepResult.skipped("skipped: " + blocker + " failed");
          failureOrigin.put(step.id(), blocker);
        } else {
          Instant startedAt = Instant.now();
          runs.stepRunning(runId, step.id(), startedAt);
          result = run(step, context);
        }
        statuses.put(step.id(), result.status());
        if (result.status() == RunStatus.FAILED) {
          anyFailed = true;
          failureOrigin.put(step.id(), step.id());
        }
        if (result.answer() != null) {
          context.record(step.id(), result.answer().json(), result.answer().body());
        }
        runs.stepFinished(runId, step.id(), result, Instant.now());
        lines.add(line(step, result));
      }
    } catch (RuntimeException e) {
      // A store that will not answer, or a bug. Either way the run has to end.
      LOG.errorf(e, "The %s run %s could not be completed", process.kind(), runId);
      anyFailed = true;
      lines.add("the run could not be completed: " + e);
    }

    RunStatus status = anyFailed ? RunStatus.FAILED : RunStatus.SUCCEEDED;
    String summary = String.join("; ", lines);
    try {
      runs.close(runId, status, summary, Instant.now());
    } catch (RuntimeException e) {
      // Nothing else can be done here: the account of the run is what could not be written.
      LOG.errorf(e, "The %s run %s finished %s but could not be closed", process.kind(), runId, status);
    }
    LOG.infof("%s run %s %s: %s", process.kind(), runId, status, summary);
  }

  /**
   * Which FAILED step blocks this one, or null.
   *
   * <p>Dependencies are read in declaration order and the first blocker wins, so a step with two
   * broken predecessors names the earlier one — the same one a person reading the list top to
   * bottom would name.
   */
  private static String blocker(
      StepDefinition step, Map<String, RunStatus> statuses, Map<String, String> failureOrigin) {
    for (String dependency : step.dependsOn()) {
      RunStatus status = statuses.get(dependency);
      if (status == RunStatus.FAILED || status == RunStatus.SKIPPED) {
        return failureOrigin.getOrDefault(dependency, dependency);
      }
    }
    return null;
  }

  /** One step's body, with a thrown exception turned into the FAILED row it should have been. */
  private static StepResult run(StepDefinition step, RunContext context) {
    try {
      return step.body().run(context);
    } catch (RuntimeException e) {
      return new StepResult(RunStatus.FAILED, null, null, null, step.id() + " threw: " + e);
    }
  }

  /** One step's contribution to the run's summary line. */
  private static String line(StepDefinition step, StepResult result) {
    String detail =
        switch (result.status()) {
          case SUCCEEDED -> result.summary() == null ? "ok" : result.summary();
          case SKIPPED, FAILED -> result.error() == null ? result.status().name() : result.error();
          default -> result.status().name();
        };
    return step.id() + " " + result.status() + " (" + detail + ")";
  }
}
