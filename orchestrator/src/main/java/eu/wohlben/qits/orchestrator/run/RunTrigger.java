package eu.wohlben.qits.orchestrator.run;

/**
 * What started a run. The wire spelling is lower case — {@code manual}, {@code scheduled} — because
 * it is a label a person reads in a list, not a constant anyone branches on.
 */
public enum RunTrigger {
  /** A person pressed Run now, or a machine posted to the route. */
  MANUAL,

  /** The cron. */
  SCHEDULED;

  /** The stored and served spelling. */
  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
