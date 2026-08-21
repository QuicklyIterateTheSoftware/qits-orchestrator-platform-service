package eu.wohlben.qits.orchestrator.process;

import eu.wohlben.qits.orchestrator.peer.PeerAnswer;
import eu.wohlben.qits.orchestrator.peer.PeerCall;
import eu.wohlben.qits.orchestrator.peer.PeerExchange;
import eu.wohlben.qits.orchestrator.run.RunStatus;
import java.util.function.Function;

/**
 * What one step did — everything the executor writes into its row.
 *
 * @param status SUCCEEDED, FAILED or SKIPPED. A body never returns PENDING or RUNNING; those are
 *     the executor's own.
 * @param call the request, or null for a step that never made one
 * @param answer the peer's reply, or null for the same reason
 * @param summary the one human line, or null
 * @param error why it failed or why it was skipped, or null
 */
public record StepResult(
    RunStatus status, PeerCall call, PeerAnswer answer, String summary, String error) {

  /** A step that never ran, with the reason a person reads: {@code dry run}, {@code skipped: …}. */
  public static StepResult skipped(String reason) {
    return new StepResult(RunStatus.SKIPPED, null, null, null, reason);
  }

  /**
   * The ordinary ending: a call was made, and the peer's status decides the verdict.
   *
   * <p><b>2xx is the only success.</b> A peer that answers 200 with an error object inside is that
   * peer's contract to fix; this service reports what it was told and stores the body whole, so a
   * reader can see the disagreement.
   *
   * @param summary reads the parsed answer and produces the human line. It is only called on a
   *     successful exchange, and it must tolerate a null tree — a bounded body does not parse.
   */
  public static StepResult of(PeerExchange exchange, Function<PeerAnswer, String> summary) {
    PeerAnswer answer = exchange.answer();
    if (answer.error() != null) {
      return new StepResult(RunStatus.FAILED, exchange.call(), answer, null, answer.error());
    }
    if (!answer.ok()) {
      return new StepResult(
          RunStatus.FAILED,
          exchange.call(),
          answer,
          null,
          exchange.call().url() + " answered " + answer.httpStatus());
    }
    String line;
    try {
      line = summary.apply(answer);
    } catch (RuntimeException e) {
      // A summary is a convenience. A peer that answered fine and a summariser that could not read
      // its shape is a step that SUCCEEDED with an unreadable caption, never a failed deletion.
      line = "answered " + answer.httpStatus() + "; the summary could not be read: " + e;
    }
    return new StepResult(RunStatus.SUCCEEDED, exchange.call(), answer, line, null);
  }
}
