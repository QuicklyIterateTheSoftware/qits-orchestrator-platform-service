package eu.wohlben.qits.orchestrator.run;

/**
 * The status vocabulary, shared by a run and a step because the UI paints them with one palette.
 *
 * <p>A RUN is only ever {@link #RUNNING}, {@link #SUCCEEDED} or {@link #FAILED}. A STEP may also be
 * {@link #PENDING} (declared, not reached yet) or {@link #SKIPPED} (a dependency failed, or the run
 * is a dry run and this step is the one that deletes).
 *
 * <p><b>A skipped step does not fail a run</b> — a skip is the consequence of a failure that is
 * already counted, and counting it twice would make one broken peer look like two.
 */
public enum RunStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED
}
