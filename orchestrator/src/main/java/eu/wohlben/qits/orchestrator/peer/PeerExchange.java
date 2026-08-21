package eu.wohlben.qits.orchestrator.peer;

/**
 * One call and its answer, together — the pair a step row is written from.
 *
 * <p>They travel as one because a step records both or neither: a request with no answer beside it
 * cannot be read, and an answer with no request beside it cannot be checked.
 */
public record PeerExchange(PeerCall call, PeerAnswer answer) {}
