package eu.wohlben.qits.orchestrator.process;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.orchestrator.peer.PeerClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a step body is given: the run it belongs to, the peer client, and what the steps before it
 * were told.
 *
 * <p><b>Answers are kept as trees, by step id.</b> The gc process hands the deployments and ci
 * answers to qits-artifacts verbatim and derives its image keep-set from the first of them, so a
 * step reading a predecessor's body is the normal case rather than an escape hatch.
 *
 * <p>Not thread-safe, and it does not need to be: one run is one thread.
 */
public final class RunContext {

  private final UUID runId;

  private final boolean dryRun;

  private final PeerClient peers;

  private final Map<String, JsonNode> answers = new HashMap<>();

  private final Map<String, String> bodies = new HashMap<>();

  public RunContext(UUID runId, boolean dryRun, PeerClient peers) {
    this.runId = runId;
    this.dryRun = dryRun;
    this.peers = peers;
  }

  public UUID runId() {
    return runId;
  }

  /** Whether this run may delete. It travels into every peer body as that peer's own flag. */
  public boolean dryRun() {
    return dryRun;
  }

  public PeerClient peers() {
    return peers;
  }

  /** Records a step's answer for the steps that depend on it. Called by the executor. */
  public void record(String stepId, JsonNode json, String body) {
    if (json != null) {
      answers.put(stepId, json);
    }
    if (body != null) {
      bodies.put(stepId, body);
    }
  }

  /** A predecessor's answer as a tree, or empty when it did not answer parseable JSON. */
  public Optional<JsonNode> answer(String stepId) {
    return Optional.ofNullable(answers.get(stepId));
  }

  /**
   * A predecessor's answer as the TEXT it arrived as.
   *
   * <p>The pins go to qits-artifacts verbatim, and verbatim means the bytes it was given rather
   * than a re-serialisation of a tree — a re-serialisation is a second opinion about a contract
   * that belongs to two other repositories.
   */
  public Optional<String> body(String stepId) {
    return Optional.ofNullable(bodies.get(stepId));
  }
}
