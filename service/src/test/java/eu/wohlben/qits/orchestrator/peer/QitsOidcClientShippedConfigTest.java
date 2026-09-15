package eu.wohlben.qits.orchestrator.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped {@code microprofile-config.properties}
 * resolves it with no {@code QITS_RESOURCE_IDP_*} or old extras env set — the "nothing configured"
 * arm every clone-alone build and every other test in this repository runs on
 * (service-client-identity-plan.md, C4).
 *
 * <p>{@link QitsOidcClientOldExtrasFallbackTest} and {@link
 * QitsOidcClientResourceOverridesOldExtrasTest} hold the other two arms — the old extras keys
 * alone, and the new resource keys winning over them — each in its own {@code @QuarkusTest} because
 * a {@code @TestProfile}'s config overrides are fixed for the life of one boot.
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    assertEquals("http://qits-platform-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("qits-platform-orchestrator", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null, so an empty secret reads
    // as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every peer: the platform's, which is the only one the idp mints.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped expression
    // regardless of what QUARKUS_OIDC_CLIENT_ARTIFACTS_CLIENT_ENABLED says — the arm every test in
    // this repository is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theArtifactsBlockIsTheOnlyOtherNamedClientAndItIsDisabled() {
    // Nothing injects it (PeerTokens mints through `qits` for every peer). It ships because a live
    // deployment holds this service's credential under the QUARKUS_OIDC_CLIENT_ARTIFACTS_* names the
    // `qits` client falls back to, and a block can be overridden by the environment only where it
    // exists: this one is what turns a leftover _CLIENT_ENABLED=true into an INERT client rather
    // than one that discovers and fetches a token at boot.
    assertEquals("false", value("quarkus.oidc-client.artifacts.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.artifacts.discovery-enabled"));
    assertEquals("false", value("quarkus.oidc-client.artifacts.early-tokens-acquisition"));

    // And there is no block per receiver any more: one audience serves all eight calls, so a client
    // per peer would be seven nothing mints through. What says a block is gone is that the key
    // answers the extension's own default — `client-enabled` is `true` and `discovery-enabled` has
    // no value at all for ANY name, invented ones included, because these are a config MAPPING's
    // defaults rather than a client somebody declared. A shipped `false` here would mean a block is
    // back.
    String[] gone = {
      PeerTarget.CONTAINERS,
      PeerTarget.CI,
      PeerTarget.DEPLOYMENTS,
      PeerTarget.PROJECTS,
      PeerTarget.WORKSPACES,
      PeerTarget.MAINTENANCE,
      PeerTarget.CONFIGURATION
    };
    for (String peer : gone) {
      assertEquals(
          "true",
          value("quarkus.oidc-client." + peer + ".client-enabled"),
          "there must be no shipped " + peer + " oidc client block left");
      assertTrue(
          ConfigProvider.getConfig()
              .getOptionalValue("quarkus.oidc-client." + peer + ".discovery-enabled", String.class)
              .isEmpty(),
          "there must be no shipped " + peer + " oidc client block left");
    }
  }
}
