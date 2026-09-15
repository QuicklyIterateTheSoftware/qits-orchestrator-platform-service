package eu.wohlben.qits.orchestrator.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has not declared {@code idp:client} yet — the OLD extras keys are set, the new
 * {@code QITS_RESOURCE_IDP_*} ones are not — resolves the {@code qits} client's id, secret and url
 * from them, byte for byte (service-client-identity-plan.md, C4). This is what keeps a deployment
 * running unchanged the moment this commit ships, before qits-deployments injects anything new.
 *
 * <p>A live deployment holds this service's credential under ONE spelling, the {@code artifacts}
 * client's (verified against {@code ComposeTemplate.java}, 2026-09-13: every {@code
 * QUARKUS_OIDC_CLIENT_<PEER>_CLIENT_ID} / {@code _CREDENTIALS_SECRET} pair it ever rendered reads
 * {@code ${ALIAS_PLATFORM_ORCHESTRATOR}} / {@code ${IDP_SECRET_PLATFORM_ORCHESTRATOR}}), so one
 * fallback pair is enough and this profile sets those three names, which is what the {@code qits}
 * client's own keys read.
 */
@QuarkusTest
@TestProfile(QitsOidcClientOldExtrasFallbackTest.OldExtrasOnly.class)
class QitsOidcClientOldExtrasFallbackTest {

  public static class OldExtrasOnly implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Raw env names, not the dotted keys — a QuarkusTestProfile override is a config source in its
      // own right, so a property expression that names one of these resolves it exactly as a real
      // environment variable would.
      return Map.of(
          "QUARKUS_OIDC_CLIENT_ARTIFACTS_CLIENT_ID", "old-extras-qits-platform-orchestrator",
          "QUARKUS_OIDC_CLIENT_ARTIFACTS_CREDENTIALS_SECRET", "old-extras-secret",
          "QUARKUS_OIDC_CLIENT_ARTIFACTS_AUTH_SERVER_URL", "http://old-extras-idp:8080/idp",
          "QUARKUS_OIDC_CLIENT_ARTIFACTS_CLIENT_ENABLED", "true");
    }
  }

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientFallsBackToTheOldArtifactsExtrasEnvNames() {
    assertEquals(
        "old-extras-qits-platform-orchestrator", value("quarkus.oidc-client.qits.client-id"));
    assertEquals("old-extras-secret", value("quarkus.oidc-client.qits.credentials.secret"));
    assertEquals(
        "http://old-extras-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
  }

  @Test
  void theRawEnvNameAndTheDottedKeyAreTwoDifferentLookups() {
    // Why the `qits` client's keys name QUARKUS_OIDC_CLIENT_ARTIFACTS_* inside `${…}` and never the
    // dotted `quarkus.oidc-client.artifacts.*`: the two spellings are separate properties, resolved
    // from separate sources. The raw name answers what the environment set; the dotted key answers
    // the `artifacts` block this file ships. Now that five more names ship a `client-enabled=false`
    // of their own, this is the assertion that says none of those shipped `false` values can ever
    // be what a `${QUARKUS_OIDC_CLIENT_…_CLIENT_ENABLED}` expression reads.
    assertEquals("true", value("QUARKUS_OIDC_CLIENT_ARTIFACTS_CLIENT_ENABLED"));
    assertEquals("false", value("quarkus.oidc-client.artifacts.client-enabled"));
  }

  @Test
  void theQitsClientStaysOffUnderTestWhateverTheEnvironmentSays() {
    // The expression on quarkus.oidc-client.qits.client-enabled reads the raw name above, so this
    // profile is the arm that would switch the client ON in a deployment. Under test it stays off:
    // %test.quarkus.oidc-client.qits.client-enabled=false is a profiled key in the same file and
    // wins over the unprofiled expression, so no suite here ever dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }
}
