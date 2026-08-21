package eu.wohlben.qits.orchestrator.control;

import eu.wohlben.qits.orchestrator.dto.ProcessDto;
import eu.wohlben.qits.orchestrator.dto.RequestDto;
import eu.wohlben.qits.orchestrator.dto.RunDetailDto;
import eu.wohlben.qits.orchestrator.dto.RunStepDto;
import eu.wohlben.qits.orchestrator.dto.RunSummaryDto;
import eu.wohlben.qits.orchestrator.dto.StepDto;
import eu.wohlben.qits.orchestrator.entity.OpRun;
import eu.wohlben.qits.orchestrator.entity.OpStep;
import eu.wohlben.qits.orchestrator.error.NoSuchProcessException;
import eu.wohlben.qits.orchestrator.error.NoSuchRunException;
import eu.wohlben.qits.orchestrator.persistence.RunStore;
import eu.wohlben.qits.orchestrator.process.ProcessRegistry;
import eu.wohlben.qits.orchestrator.process.TechnicalProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The read side: process definitions and run records, in the shapes the API serves.
 *
 * <p>It sits in the domain jar because the shapes are the context's, not the web layer's — {@code
 * service}'s controllers do routing, roles and status codes and nothing else.
 */
@ApplicationScoped
public class Runs {

  @Inject ProcessRegistry registry;

  @Inject RunStore store;

  /** Every registered process, with its steps. */
  public List<ProcessDto> processes() {
    return registry.all().stream().map(Runs::describe).toList();
  }

  /**
   * The newest runs of one kind.
   *
   * <p><b>An unknown kind is a 404, not an empty list.</b> "this process has never run" and "there
   * is no such process" are different answers, and a client that cannot tell them apart shows an
   * empty page for a typo.
   */
  public List<RunSummaryDto> runs(String kind, int limit) {
    registry.byKind(kind).orElseThrow(() -> new NoSuchProcessException(kind));
    return store.runs(kind, limit).stream().map(Runs::summarise).toList();
  }

  /** One run with its steps. */
  public RunDetailDto run(UUID id) {
    OpRun run = store.run(id).orElseThrow(() -> new NoSuchRunException(id));
    List<RunStepDto> steps = store.steps(id).stream().map(Runs::describe).toList();
    return new RunDetailDto(
        run.id,
        run.kind,
        run.trigger,
        run.dryRun,
        run.status,
        run.startedAt,
        run.finishedAt,
        run.summary,
        steps);
  }

  private static ProcessDto describe(TechnicalProcess process) {
    return new ProcessDto(
        process.kind(),
        process.name(),
        process.description(),
        process.steps().stream()
            .map(step -> new StepDto(step.id(), step.name(), step.target(), step.dependsOn()))
            .toList());
  }

  private static RunSummaryDto summarise(OpRun run) {
    return new RunSummaryDto(
        run.id,
        run.kind,
        run.trigger,
        run.dryRun,
        run.status,
        run.startedAt,
        run.finishedAt,
        run.summary);
  }

  private static RunStepDto describe(OpStep step) {
    RequestDto request =
        step.requestUrl == null
            ? null
            : new RequestDto(step.requestMethod, step.requestUrl, step.requestBody);
    return new RunStepDto(
        step.stepId,
        step.name,
        step.target,
        dependsOn(step.dependsOn),
        step.status,
        step.startedAt,
        step.finishedAt,
        step.httpStatus,
        request,
        step.responseBody,
        step.error,
        step.summary);
  }

  /** The stored comma-separated edges, back as a list. Empty rather than null: the UI iterates it. */
  private static List<String> dependsOn(String stored) {
    if (stored == null || stored.isBlank()) {
      return List.of();
    }
    return Arrays.stream(stored.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
  }
}
