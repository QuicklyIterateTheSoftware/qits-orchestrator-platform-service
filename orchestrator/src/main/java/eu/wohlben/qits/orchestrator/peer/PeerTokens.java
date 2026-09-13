package eu.wohlben.qits.orchestrator.peer;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The one named oidc client, {@code qits} (service-client-identity-plan.md, C4), replacing the
 * eight peer-specific clients this class used to hold.
 *
 * <p><b>One audience for every call now, {@code qits-platform}</b> — the plan's open calling
 * model: a service calling a service may do basically everything, so one token minted for one
 * audience is good for all eight peers. The old shape cut a token FOR one peer at a time because
 * qits-artifacts refused a bearer addressed to qits-containers; that refusal is gone now that every
 * receiver accepts {@code qits-platform}.
 *
 * <p><b>The switch is the extension's own</b>, {@code quarkus.oidc-client.qits.client-enabled},
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
  @NamedOidcClient("qits")
  OidcClient qits;

  /** Caches and refreshes the one token, so a seventeen-step run is not seventeen token requests. */
  private final TokensHelper helper = new TokensHelper();

  /** The bearer for one peer, or empty when the client is disabled or cannot mint. */
  public Optional<String> token(String target) {
    if (!enabled()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              helper.getTokens(qits).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for %s: %s", target, e.toString());
      return Optional.empty();
    }
  }

  private boolean enabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue("quarkus.oidc-client.qits.client-enabled", Boolean.class)
        .orElse(false);
  }
}
