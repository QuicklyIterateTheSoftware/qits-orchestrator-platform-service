package eu.wohlben.qits.orchestrator.run;

import eu.wohlben.qits.orchestrator.peer.PeerTarget;
import eu.wohlben.qits.orchestrator.process.StepDefinition;
import eu.wohlben.qits.orchestrator.process.TechnicalProcess;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * A process the executor tests write themselves, so what is under test is the EXECUTOR rather than
 * the gc definition.
 *
 * <p>The steps are a static field: {@code RunExecutor} reads {@code steps()} twice per run (once to
 * write the rows, once to walk them) and both reads must see the same list. A test sets the script
 * before it starts a run and does not touch it again.
 */
@ApplicationScoped
public class ProbeProcess implements TechnicalProcess {

  public static final String KIND = "probe";

  /** What this process is, for the run about to start. */
  public static volatile List<StepDefinition> script = List.of();

  /** A step that succeeds without calling anything, for ordering tests. */
  public static StepDefinition succeeding(String id, String... dependsOn) {
    return new StepDefinition(
        id,
        id,
        PeerTarget.CONTAINERS,
        List.of(dependsOn),
        context -> new StepResultBuilder().succeeded(id + " ok"));
  }

  /**
   * A step the process chose not to make — the dry-run case, in the shape the executor must NOT
   * treat as a failure.
   */
  public static StepDefinition policySkipping(String id, String... dependsOn) {
    return new StepDefinition(
        id,
        id,
        PeerTarget.CONTAINERS,
        List.of(dependsOn),
        context -> eu.wohlben.qits.orchestrator.process.StepResult.skipped("dry run"));
  }

  /** A step that fails without calling anything. */
  public static StepDefinition failing(String id, String... dependsOn) {
    return new StepDefinition(
        id,
        id,
        PeerTarget.CONTAINERS,
        List.of(dependsOn),
        context -> new StepResultBuilder().failed(id + " broke"));
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String name() {
    return "Probe";
  }

  @Override
  public String description() {
    return "A process the executor suite writes step by step.";
  }

  @Override
  public List<StepDefinition> steps() {
    return script;
  }
}
