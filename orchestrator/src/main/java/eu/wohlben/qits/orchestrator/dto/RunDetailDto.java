package eu.wohlben.qits.orchestrator.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run with its steps — what {@code GET /runs/{id}} serves, and what the UI polls every two
 * seconds while the run is RUNNING.
 *
 * <p>The run's own fields are {@link RunSummaryDto}'s, repeated rather than nested: a client
 * rendering a run page should not have to reach through a wrapper for the status.
 */
public record RunDetailDto(
    UUID id,
    String kind,
    String trigger,
    boolean dryRun,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String summary,
    List<RunStepDto> steps) {}
