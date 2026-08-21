package eu.wohlben.qits.orchestrator.dto;

import java.util.List;

/**
 * One step of a process DEFINITION, as {@code GET /processes} serves it.
 *
 * <p>It is the shape the UI lays out before any run exists — the cards and the lines between them
 * are drawn from {@code dependsOn} alone.
 *
 * @param id the stable step id, e.g. {@code containers.images}
 * @param name the human line in the card's header
 * @param target the peer this step calls
 * @param dependsOn the step ids it waits for; empty, never null
 */
public record StepDto(String id, String name, String target, List<String> dependsOn) {}
