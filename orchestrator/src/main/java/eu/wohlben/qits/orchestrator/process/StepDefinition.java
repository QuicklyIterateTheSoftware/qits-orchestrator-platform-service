package eu.wohlben.qits.orchestrator.process;

import java.util.List;

/**
 * One step of a technical process: its identity, its peer, its edges and its body.
 *
 * <p><b>The four descriptive fields are the wire contract</b> — {@code GET /processes} serves
 * exactly {@code {id, name, target, dependsOn}} — and {@link #body} is the code behind them, which
 * no route ever exposes.
 *
 * @param id the stable id, e.g. {@code containers.images}. It is what {@link #dependsOn} names, what
 *     a step row stores as {@code step_id} and what the API reports as the step's {@code id}.
 * @param name the human line in a card's header
 * @param target the peer, one of {@code PeerTarget}'s constants
 * @param dependsOn the ids this step waits for. An edge is a REQUIREMENT, not an ordering hint: a
 *     dependency that failed or was skipped skips this step too.
 * @param body what the step does. It never throws — see {@link StepBody}.
 */
public record StepDefinition(
    String id, String name, String target, List<String> dependsOn, StepBody body) {

  public StepDefinition {
    dependsOn = List.copyOf(dependsOn);
  }

  /**
   * A step's work: send one request, read the answer, say what happened in one line.
   *
   * <p><b>It returns a {@link StepResult} and does not throw.</b> A failure is a row with an error
   * in it, and a body that threw would have to be caught somewhere that has no idea what the call
   * was — so the call and its verdict stay in the same place.
   */
  @FunctionalInterface
  public interface StepBody {
    StepResult run(RunContext context);
  }
}
