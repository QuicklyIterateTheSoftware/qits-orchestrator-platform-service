package eu.wohlben.qits.orchestrator.process;

/**
 * Why a step was skipped — and therefore whether the steps after it may still run.
 *
 * <p><b>The two look identical on the wire and are not the same thing.</b> Both are {@code status:
 * SKIPPED} with a sentence in {@code error}, because both mean "this step made no call". What
 * differs is what they say about the run: a {@link #POLICY} skip is the process doing what it was
 * asked, and a {@link #FAILURE} skip is the consequence of something breaking.
 *
 * <p><b>Measured live.</b> The first real dry run on the platform answered 200 to all nine calls and
 * still reported {@code usage.after} as {@code skipped: artifacts.sweep failed} — because the
 * executor read every non-SUCCEEDED dependency as a failure, and a dry run skips the sweep on
 * purpose. A green run that reads as broken is worse than a red one, so the distinction is a field
 * rather than a convention.
 */
public enum SkipKind {

  /**
   * The process chose not to make this call. A dry run does not sweep — that is the run doing
   * exactly what was asked, so it is not a failure and it does not cascade: a dependent of a
   * policy-skipped step runs normally.
   */
  POLICY,

  /**
   * A dependency FAILED, so this step could not honestly run. It cascades: its own dependents are
   * skipped too, and they name the step that actually failed rather than this one.
   */
  FAILURE
}
