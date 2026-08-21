package eu.wohlben.qits.orchestrator.error;

/** A kind no process answers to — a 404, never an empty list. */
public class NoSuchProcessException extends OrchestratorException {

  public NoSuchProcessException(String kind) {
    super(404, "no technical process of kind '" + kind + "'");
  }
}
