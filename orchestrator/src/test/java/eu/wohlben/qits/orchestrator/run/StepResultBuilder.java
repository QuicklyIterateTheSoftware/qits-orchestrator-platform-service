package eu.wohlben.qits.orchestrator.run;

import eu.wohlben.qits.orchestrator.process.StepResult;

/**
 * Two results the executor suite needs and no production path produces: a verdict with no call
 * behind it. {@link StepResult}'s own factories take an exchange, because every real step makes one.
 */
final class StepResultBuilder {

  StepResult succeeded(String summary) {
    return new StepResult(RunStatus.SUCCEEDED, null, null, summary, null);
  }

  StepResult failed(String error) {
    return new StepResult(RunStatus.FAILED, null, null, null, error);
  }
}
