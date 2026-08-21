package eu.wohlben.qits.orchestrator.dto;

/**
 * The request a step sent, as the run detail serves it.
 *
 * <p><b>{@code body} is a STRING carrying JSON text</b>, not an embedded object. It is stored as it
 * went out and served as it was stored: a re-serialisation would be a second opinion about what was
 * sent, and the keep-set inside it is the thing an investigation is looking at.
 *
 * @param method GET or POST
 * @param url the absolute url
 * @param body the JSON text, or null for a GET
 */
public record RequestDto(String method, String url, String body) {}
