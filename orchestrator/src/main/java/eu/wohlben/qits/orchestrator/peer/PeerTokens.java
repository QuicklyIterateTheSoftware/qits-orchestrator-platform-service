package eu.wohlben.qits.orchestrator.peer;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The eight named oidc clients — one per peer — and the reason there are eight rather than one.
 *
 * <p><b>A token is cut FOR one service.</b> qits-artifacts refuses a bearer whose audience names
 * qits-containers, so a single client would only be able to talk to one peer. The client id is the
 * same everywhere (this service) and only {@code grant-options.client.audience} differs, which is
 * also the one setting the shipped defaults deliberately leave unset: it is environment-qualified
 * (`dev-qits-artifacts`) and an image every environment shares must not name a tier it may not be
 * running in.
 *
 * <p><b>The switch is the extension's own</b>, {@code quarkus.oidc-client.<peer>.client-enabled},
 * false in the shipped properties. There is no key of ours beside it — one switch cannot disagree
 * with itself. Off, this answers empty and the call goes out with the forward-auth headers alone,
 * which is what a platform running its peers open on qits-net accepts.
 *
 * <p><b>A token this cannot mint is empty rather than an exception</b>, the deployer's stance: the
 * refusal that matters belongs to the call itself. An anonymous call to a guarded peer comes back
 * 401, and the step records the url and the status — which is more useful than a mint failure one
 * layer earlier.
 */
@ApplicationScoped
public class PeerTokens {

  private static final Logger LOG = Logger.getLogger(PeerTokens.class);

  /** The mint is not the call: this bounds the hop to idp, not the hop to the peer. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @Inject
  @NamedOidcClient("artifacts")
  OidcClient artifacts;

  @Inject
  @NamedOidcClient("containers")
  OidcClient containers;

  @Inject
  @NamedOidcClient("ci")
  OidcClient ci;

  @Inject
  @NamedOidcClient("deployments")
  OidcClient deployments;

  @Inject
  @NamedOidcClient("projects")
  OidcClient projects;

  @Inject
  @NamedOidcClient("workspaces")
  OidcClient workspaces;

  @Inject
  @NamedOidcClient("maintenance")
  OidcClient maintenance;

  @Inject
  @NamedOidcClient("configuration")
  OidcClient configuration;

  /**
   * Caches and refreshes each peer's token, so a seventeen-step run is not seventeen token requests.
   */
  private final Map<String, TokensHelper> helpers =
      Map.of(
          PeerTarget.ARTIFACTS, new TokensHelper(),
          PeerTarget.CONTAINERS, new TokensHelper(),
          PeerTarget.CI, new TokensHelper(),
          PeerTarget.DEPLOYMENTS, new TokensHelper(),
          PeerTarget.PROJECTS, new TokensHelper(),
          PeerTarget.WORKSPACES, new TokensHelper(),
          PeerTarget.MAINTENANCE, new TokensHelper(),
          PeerTarget.CONFIGURATION, new TokensHelper());

  /** The bearer for one peer, or empty when its client is disabled or cannot mint. */
  public Optional<String> token(String target) {
    if (!enabled(target)) {
      return Optional.empty();
    }
    OidcClient client = client(target);
    TokensHelper helper = helpers.get(target);
    if (client == null || helper == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              helper.getTokens(client).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for %s: %s", target, e.toString());
      return Optional.empty();
    }
  }

  /**
   * Read per call rather than injected as four booleans: the key name carries the peer, and one
   * lookup keeps this class from growing a field per peer twice over.
   */
  private boolean enabled(String target) {
    return ConfigProvider.getConfig()
        .getOptionalValue("quarkus.oidc-client." + target + ".client-enabled", Boolean.class)
        .orElse(false);
  }

  private OidcClient client(String target) {
    return switch (target) {
      case PeerTarget.ARTIFACTS -> artifacts;
      case PeerTarget.CONTAINERS -> containers;
      case PeerTarget.CI -> ci;
      case PeerTarget.DEPLOYMENTS -> deployments;
      case PeerTarget.PROJECTS -> projects;
      case PeerTarget.WORKSPACES -> workspaces;
      case PeerTarget.MAINTENANCE -> maintenance;
      case PeerTarget.CONFIGURATION -> configuration;
      default -> null;
    };
  }
}
