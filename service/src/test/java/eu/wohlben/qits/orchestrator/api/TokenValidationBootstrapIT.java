package eu.wohlben.qits.orchestrator.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove.
 *
 * <p>The shipped tenant is {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}},
 * and <b>no suite in this repository turns that gate on at all</b> — the test
 * application.properties says so in as many words ("the OIDC tenant is disabled because
 * qits.auth.machine.required defaults false"), which is what keeps a clone-alone {@code ./mvnw
 * verify} free of an issuer. Every {@code @QuarkusTest} here therefore runs under the {@code test}
 * profile's dev user, and {@link PackagedSurfaceIT} proves only the OTHER track of identity — the
 * forward-auth headers qits-gateway asserts. The consequence is that the entire shipped {@code
 * quarkus.oidc.*} block — auth-server-url with {@code discovery-enabled=false} and {@code
 * jwks-path=jwks} joined onto it, the boot-time fetch that {@code connection-delay} retries,
 * audience enforcement, groups&rarr;roles mapping — is exercised NOWHERE. This is the one place it
 * runs. The far side is {@link MockIdp}, whose recordings make the interaction assertable on
 * <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The stories
 * are browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The route both stories drive is {@code GET /orchestrator/api/processes}</b>, the process
 * catalogue, and it is chosen as the least side-effectful read this service has. Every route here
 * is {@code @RolesAllowed({"qits:admin", "qits:system"})}, so the machine role reaches all of them
 * and the choice is about what the request DOES, not about what it takes:
 *
 * <ul>
 *   <li>it names nothing. It is the only guarded read with no path parameter — the run listing
 *       carries a process kind, the run detail a run id;
 *   <li>it opens no store and calls no peer. {@code Runs.processes()} reads the {@code
 *       ProcessRegistry}, which is CDI discovery and nothing else, so the answer is a fact about
 *       THIS artifact rather than about a peer this IT would then have to stand in for — the
 *       qits-platform-deployments pin-ledger property, one step further in: not even a row is read;
 *   <li>and it is the read whose answer is the pinned contract. {@code qits-orchestrator-plan.md}
 *       in the qits-qits wrapper fixes the step ids and the edges between them and four
 *       repositories build against them, so what the catalogue serves is the plan itself.
 * </ul>
 *
 * <p><b>The write is the obvious other candidate and it is worse on every count</b>, which is why
 * no story here posts one. {@code POST /processes/{kind}/runs} starts real deletions on four other
 * services; it is a 202 that hands the work to a single-threaded executor, it writes a run and
 * eleven step rows, and against the dead peer addresses this profile inherits it would spend the
 * story's time recording eleven failures. A story documenting "a machine may do this" would be a
 * story documenting a deletion. {@link PackagedSurfaceIT} already starts one, on its own terms, for
 * a different claim: that a failure is carried all the way into a readable row.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@link PackagedSurfaceIT} is this module's other integration
 * test and a good half of it is about the CLIENT — the base href at the root, the deep links, the
 * fallback that must not swallow {@code /orchestrator} — which the userflow pipeline deliberately
 * does not build ({@code -Dquarkus.quinoa=false}, since the qits-platform-spa-orchestrator
 * submodule arrives EMPTY in a step container). A blanket {@code -DskipITs=false} would make that
 * run red on a test that is right. {@code .config/qits/ci-event-userflows.yml} names this class
 * instead ({@code -DskipITs=false "-Dit.test=TokenValidationBootstrapIT"}), which is also what
 * keeps the userflow pipeline about these stories and nothing else — and keeps the property's own
 * meaning ("run everything") intact for the {@code native} profile in service/pom.xml that sets it.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-orchestrator-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-reads-the-platform-s-deletion-plan";

  private static final String PROCESSES = "/orchestrator/api/processes";

  /** The one process this platform has, and the kind every step id below hangs under. */
  private static final String GC = "find { it.kind == 'gc' }";

  /**
   * {@link PackagedSurfaceIT.PackagedUnderTarget} — the {@code QITS_RESOURCE_DB_*} triple on this
   * JVM's embedded postgres, the four peer addresses pointed at a port nothing listens on, and the
   * clock switched off, all parked in system properties because a test profile is instantiated in
   * more than one classloader — <b>plus the two things these stories are about</b>: the gate that
   * turns the shipped OIDC tenant on, and where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-platform-orchestrator
   * needs in order to boot at all is one answer, it is written out at length over there (the triple
   * is mandatory: the domain jar's datasource expressions have no default behind them), and a
   * second copy of the parking trick would be a second place for it to drift. What is added here is
   * only the seams these stories move.
   *
   * <p><b>{@code quarkus.scheduler.enabled=false} is inherited and it is load-bearing here rather
   * than tidy.</b> The gc schedule is a cron at 03:00 UTC, and a CI run that happened to straddle
   * that minute would start an unattended, non-dry deletion run out of a test JVM. It cannot reach
   * a real peer — every address below is dead — but a story is not the place to find that out.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()},
   * which parks its coordinates (and its keypair) in system properties for the same classloader
   * reason — that is also how a story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
   * -D} arguments on a jar that was already built, so a build-time key would be silently ignored
   * and these tests would prove the opposite of what they say.
   *
   * <p><b>There is no telemetry or event-bus line to darken, and that is a fact about this
   * repository rather than an omission.</b> Nothing here depends on qits-eventstream or on an
   * opentelemetry extension — the poms carry neither — so the dial-outs a boot could make are
   * exactly two kinds. The first is the JWKS fetch, which is pointed at {@link MockIdp} below and
   * is the whole point of the file. The second is the six peers, and they never dial at boot: a
   * peer is called only from inside a run, the four named oidc clients ship {@code
   * client-enabled=false} with {@code early-tokens-acquisition=false} beside them, and the unnamed
   * default client ships disabled too. The addresses are neutralised anyway — the four inherited
   * ones by the parent, the remaining two below — because a defaulted {@code qits-projects:8080}
   * resolves to nothing in a step container and "resolves to nothing" is a slower failure than
   * "connection refused".
   */
  public static class PackagedWithMockIdp extends PackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name — the
     * difference from qits-githost's IT, which hands its launched process {@code
     * QITS_AUTH_MACHINE_AUDIENCE} because the shipped expression there reads that variable. Here
     * {@code qits.auth.machine.audience=qits-platform-orchestrator} is spelled out in {@code
     * application.properties} and {@code quarkus.oidc.token.audience} references it, so the
     * audience under test is the shipped one and there is no expression to feed. A deployment still
     * overrides it by environment.
     */
    static final String AUDIENCE = "qits-platform-orchestrator";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. The
      // application.properties block says what flipping it implies — with it on there IS a tenant,
      // and the tenant fetches a JWKS at boot — and this is where that is proved rather than
      // described. It also says there is no third state, which is why nothing else is set with it.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam these stories move: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and jwks-path stays `jwks`, joined onto
      // this URL.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // The two peers the parent does not name, pointed at the same dead address as the four it
      // does. Reading its value back rather than choosing a second one keeps one dead port in this
      // profile: a second helper here would be a second thing to keep true.
      String dead = overrides.get("qits.orchestrator.targets.artifacts-url");
      overrides.put("qits.orchestrator.targets.projects-url", dead);
      overrides.put("qits.orchestrator.targets.workspaces-url", dead);
      return overrides;
    }
  }

  @UserStory(
      value = "On start, the orchestrator fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-orchestrator must validate service bearers before any
      caller arrives: at startup it fetches the signing keys (JWKS) from qits-platform-idp —
      discovery stays off, the path is configured — so the very first machine request is
      accepted. What that bearer then opens is the plan itself: the technical processes this
      service runs, their steps, and the dependency edges between them. That listing is the
      contract four repositories build against, and it is what a caller reads before it asks for
      a run — the run being minutes of somebody else's pruning, on four other services' stores.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-orchestrator starts with the OIDC tenant on, beside a reachable"
            + " qits-platform-idp");
    given().get("/orchestrator/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. Readiness above is deliberately independent of that fetch — the shipped config
    // explains why: tying it to another service's would make a cold boot a question of ordering —
    // so a 200 there is not the claim. The recording is.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .happened("qits-platform-orchestrator", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = this service, roles in `groups`) opens the process catalogue — no path
    // parameter, nothing named, no store opened and no peer called.
    String peerToken =
        idp.token()
            .subject("a-platform-service")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + peerToken)
        .get(PROCESSES)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("kind", hasItem("gc"))
        .body(GC + ".name", equalTo("Garbage collection"))
        .body(GC + ".description", notNullValue());
    story
        .happened(
            "a platform service",
            "qits-platform-orchestrator",
            "GET /orchestrator/api/processes (Bearer, groups=[qits:system])")
        .as("catalogue-served");

    // End (c), and it is what makes this answer worth guarding rather than a greeting: the plan the
    // catalogue serves is the pinned one. The step ids and their edges are fixed by
    // qits-orchestrator-plan.md and read by four repositories, and augmentation is where a process
    // bean could quietly fail to be discovered — ProcessRegistry finds them by CDI, so a fast-jar
    // that lost the bean would answer 200 with an empty list and nothing else here would notice.
    //
    // The edge asserted is the fail-closed one and it is asserted BECAUSE it is a promise made on
    // the wire: `artifacts.plan` waits on both pin reads, so a pin read that failed skips the plan,
    // the sweep behind it and the image collection beside it before any body runs. A reader of this
    // document can see that without running a night.
    given()
        .header("Authorization", "Bearer " + peerToken)
        .get(PROCESSES)
        .then()
        .statusCode(200)
        .body(GC + ".steps.size()", equalTo(11))
        .body(GC + ".steps.id", hasItem("artifacts.sweep"))
        .body(
            GC + ".steps.find { it.id == 'artifacts.plan' }.dependsOn",
            contains("pins.deployments", "pins.ci"));
    story
        .happened(
            "a platform service",
            "qits-platform-orchestrator",
            "reads the gc plan: 11 steps, artifacts.plan depends on both pin reads (fail-closed)")
        .as("plan-is-the-pinned-one");
  }

  @UserStory(
      value = "A stranger's token never reads the platform's deletion plan",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A request with no credential at all is
      refused, because there is no anonymous route in this service and there must never be one:
      what the surface behind it starts is a deletion run on four other services' stores. A
      token signed by a key the published JWKS never carried, or minted for another service's
      audience, is refused the same way — however well-formed it looks: both are 401 and not
      403, because the credential never became an identity and there is no caller to have been
      forbidden. A token addressed here and signed correctly but carrying a role this service has
      never heard of gets the other answer, 403 — it authenticated and covers nothing.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // The first door, and it is this service's own shape rather than the fleet's. The gate being on
    // changes nothing about it: with the tenant enabled there are two mechanisms at the door, the
    // bearer's and qits-gateway's forward-auth pair, and a request carrying neither satisfies
    // neither. PackagedSurfaceIT pins the same 401 with the gate OFF; the pair is what says the
    // refusal is not an artefact of how this service happens to be configured.
    given().get(PROCESSES).then().statusCode(401);
    story
        .happened(
            "an unauthenticated caller",
            "qits-platform-orchestrator",
            "GET /orchestrator/api/processes (no credential) -> 401")
        .as("anonymous-refused");

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(PROCESSES)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-orchestrator",
            "GET /orchestrator/api/processes (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(PROCESSES)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-orchestrator",
            "GET /orchestrator/api/processes (another service's audience) -> 401")
        .as("wrong-audience-refused");

    // The last door, and the one that proves the groups→roles mapping really ran rather than being
    // waved through: `qits:reader` is a real platform role and it is not one of the two this
    // service's routes name. Minted into a token addressed here it authenticates perfectly and
    // still covers nothing. The two that DO open this route — qits:admin and qits:system — are
    // deliberately not distinguished anywhere in this service, so there is no narrower ceiling to
    // draw: an operator presses Run now in a browser and a machine may post the same run.
    String readerToken =
        idp.token()
            .subject("somebody-elses-service")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:reader")
            .mint();
    given().header("Authorization", "Bearer " + readerToken).get(PROCESSES).then().statusCode(403);
    story
        .happened(
            "a caller with the wrong role",
            "qits-platform-orchestrator",
            "GET /orchestrator/api/processes (Bearer, groups=[qits:reader]) -> 403")
        .as("wrong-role-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-platform-orchestrator",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "catalogue-served");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "plan-is-the-pinned-one");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "anonymous-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-role-refused");
  }
}
