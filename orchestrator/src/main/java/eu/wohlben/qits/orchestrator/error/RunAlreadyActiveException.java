package eu.wohlben.qits.orchestrator.error;

import java.util.UUID;

/**
 * A run of this kind is already going — a 409.
 *
 * <p><b>One run at a time per kind is a safety property, not a convenience.</b> Two gc runs
 * overlapping would compute two pin sets against two moments and hand each to a deleter the other
 * is also driving; the second run's plan would be a photograph of a store the first is emptying.
 * The message names the run that holds the lock, so the caller can go and read it.
 */
public class RunAlreadyActiveException extends OrchestratorException {

  private final UUID activeRunId;

  public RunAlreadyActiveException(String kind, UUID activeRunId) {
    super(409, "a " + kind + " run is already active: " + activeRunId);
    this.activeRunId = activeRunId;
  }

  public UUID activeRunId() {
    return activeRunId;
  }
}
