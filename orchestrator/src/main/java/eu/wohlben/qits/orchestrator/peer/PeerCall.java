package eu.wohlben.qits.orchestrator.peer;

/**
 * A request as it went out — the three fields a step records and the UI shows when a card is
 * clicked.
 *
 * @param method GET or POST; nothing here deletes by verb
 * @param url the absolute url, target base plus path
 * @param body the JSON body, or null for a GET
 */
public record PeerCall(String method, String url, String body) {}
