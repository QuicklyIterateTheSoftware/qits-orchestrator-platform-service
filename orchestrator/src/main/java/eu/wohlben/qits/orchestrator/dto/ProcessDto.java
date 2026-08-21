package eu.wohlben.qits.orchestrator.dto;

import java.util.List;

/**
 * One technical process, as {@code GET /processes} serves it: what it is and what it would do.
 *
 * @param kind the id in every url, e.g. {@code gc}
 * @param name the human name
 * @param description one paragraph a person reads before pressing the button
 * @param steps the steps in declaration order
 */
public record ProcessDto(String kind, String name, String description, List<StepDto> steps) {}
