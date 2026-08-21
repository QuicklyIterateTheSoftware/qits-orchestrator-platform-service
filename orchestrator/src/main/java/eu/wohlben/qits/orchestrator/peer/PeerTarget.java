package eu.wohlben.qits.orchestrator.peer;

/**
 * The four peers, by their wire name.
 *
 * <p>Constants rather than an enum because the same string is three things at once: the value a
 * step reports as its {@code target}, the middle of the config key {@code
 * qits.orchestrator.targets.<name>-url}, and the name of the oidc client that mints for it. An enum
 * would have to spell the mapping out three times; a string spells it once.
 */
public final class PeerTarget {

  /** qits-artifacts — the registry's own GC engine, plan and sweep. */
  public static final String ARTIFACTS = "artifacts";

  /** qits-containers — the platform's docker socket: images, volumes and buildkit cache. */
  public static final String CONTAINERS = "containers";

  /** qits-ci — the daemon-binary pin. */
  public static final String CI = "ci";

  /** qits-platform-deployments — the image shas a restart or a rollback would pull. */
  public static final String DEPLOYMENTS = "deployments";

  private PeerTarget() {}
}
