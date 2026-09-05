package eu.wohlben.qits.orchestrator.run;

/**
 * What started a run. The wire spelling is lower case — {@code manual}, {@code scheduled}, {@code
 * event} — because it is a label a person reads in a list, not a constant anyone branches on.
 *
 * <p><b>Which is why adding a value is safe.</b> The client renders {@code run.trigger} as text and
 * nothing on either side switches on it, so an older SPA shown a run started by a value it has never
 * heard of prints that value rather than breaking. Keep it that way: the moment something branches
 * on these strings, a new value becomes a coordinated release.
 */
public enum RunTrigger {
  /** A person pressed Run now, or a machine posted to the route. */
  MANUAL,

  /** The cron. */
  SCHEDULED,

  /**
   * Something on the platform happened that made a collection worth making now — today, a wave of
   * qits-deployments' {@code DeploymentActive} settling, debounced by {@code GcDeployTrigger}. It is
   * the trigger's PROVENANCE and not its policy: an event-started run is a real, non-dry run and
   * runs the same steps the cron's does.
   */
  EVENT;

  /** The stored and served spelling. */
  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
