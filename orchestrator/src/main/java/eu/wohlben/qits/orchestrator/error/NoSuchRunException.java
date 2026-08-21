package eu.wohlben.qits.orchestrator.error;

/**
 * No run with that id — a 404.
 *
 * <p>The id is a STRING because a malformed one and an absent one are the same question from the
 * caller's side, and the route should not have to make two refusals out of one mistake.
 */
public class NoSuchRunException extends OrchestratorException {

  public NoSuchRunException(Object id) {
    super(404, "no run '" + id + "'");
  }
}
