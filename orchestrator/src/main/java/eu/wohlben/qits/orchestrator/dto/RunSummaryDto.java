package eu.wohlben.qits.orchestrator.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One run, as the run LIST serves it — no steps, because a list of runs is read to choose one.
 *
 * @param id the run id, which {@code GET /runs/{id}} takes
 * @param kind the process
 * @param trigger {@code manual} or {@code scheduled}
 * @param dryRun whether this run was allowed to delete
 * @param status RUNNING, SUCCEEDED or FAILED
 * @param startedAt when it opened
 * @param finishedAt when it closed, or null while it runs
 * @param summary the run in one line, or null while it runs
 */
public record RunSummaryDto(
    UUID id,
    String kind,
    String trigger,
    boolean dryRun,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String summary) {}
