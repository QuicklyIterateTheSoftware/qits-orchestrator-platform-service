package eu.wohlben.qits.orchestrator.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.orchestrator.peer.FakePeers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST boundary: the shapes, the status codes and the refusals.
 *
 * <p>The addresses are the shipped ones — the suite inherits {@code
 * quarkus.rest.path=/orchestrator/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p><b>No test sends an identity header</b>, and that is not a hole: qits-auth-core ships a {@code
 * %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped {@code
 * @RolesAllowed} pair is exercised rather than bypassed. That a real request must carry the pair is
 * pinned in {@code PackagedSurfaceIT}, where the identity contract is real.
 *
 * <p>The peers are faked, so a run started here reaches no network.
 */
@QuarkusTest
class ProcessApiTest {

  private static final String BASE = "/orchestrator/api";

  @Inject FakePeers peers;

  @BeforeEach
  void scriptTheHappyPath() {
    peers.reset();
    peers.answer(
        "/containers/api/gc/usage",
        FakePeers.Scripted.ok(
            "{\"images\":{\"sizeBytes\":10,\"reclaimableBytes\":2},\"buildCache\":{\"sizeBytes\":4}}"));
    peers.answer(
        "/platform-deployments/api/pins",
        FakePeers.Scripted.ok("{\"pins\":[{\"applicationName\":\"qits-ci\",\"shas\":[\"abc\"]}]}"));
    peers.answer(
        "/ci/api/daemon",
        FakePeers.Scripted.ok("{\"daemonName\":\"qits-ci-daemon\",\"daemonVersion\":\"1\"}"));
    peers.answer("/artifacts/api/gc/plan", FakePeers.Scripted.ok("{\"summary\":{\"executable\":true}}"));
    peers.answer("/artifacts/api/gc/sweep", FakePeers.Scripted.ok("{\"sweep\":{\"blobsUnlinked\":0}}"));
    peers.answer("/containers/api/gc/images", FakePeers.Scripted.ok("{\"removed\":[],\"kept\":[]}"));
    peers.answer("/containers/api/gc/volumes", FakePeers.Scripted.ok("{\"removed\":[],\"kept\":[]}"));
    peers.answer(
        "/containers/api/gc/build-cache",
        FakePeers.Scripted.ok("{\"host\":{\"reclaimedBytes\":0},\"builders\":[]}"));
  }

  /** Starts a run and returns its id. */
  private String start(boolean dryRun) {
    return given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":" + dryRun + "}")
        .when()
        .post(BASE + "/processes/gc/runs")
        .then()
        .statusCode(202)
        .extract()
        .path("id");
  }

  /** Polls the run the way the client does, until it is no longer RUNNING. */
  private String awaitClosed(String id) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given().when().get(BASE + "/runs/" + id).then().statusCode(200).extract().path("status");
      if (!"RUNNING".equals(status)) {
        return status;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("run " + id + " never finished");
  }

  @Test
  void theProcessListingIsTheDefinitionTheClientDrawsTheGraphFrom() {
    given()
        .when()
        .get(BASE + "/processes")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("kind", hasItem("gc"))
        .body("find { it.kind == 'gc' }.name", equalTo("Garbage collection"))
        .body("find { it.kind == 'gc' }.description", notNullValue())
        .body("find { it.kind == 'gc' }.steps.size()", equalTo(9))
        .body("find { it.kind == 'gc' }.steps[0].id", equalTo("usage.before"))
        .body("find { it.kind == 'gc' }.steps[0].target", equalTo("containers"))
        .body("find { it.kind == 'gc' }.steps[0].dependsOn", equalTo(java.util.List.of()))
        .body("find { it.kind == 'gc' }.steps[3].id", equalTo("artifacts.plan"))
        .body(
            "find { it.kind == 'gc' }.steps[3].dependsOn",
            contains("pins.deployments", "pins.ci"));
  }

  @Test
  void aRunIsAcceptedWithItsIdAndTheListingShowsItAfterwards() {
    String id = start(false);
    assertEquals("SUCCEEDED", awaitClosed(id));

    given()
        .when()
        .get(BASE + "/processes/gc/runs?limit=5")
        .then()
        .statusCode(200)
        .body("id", hasItem(id))
        .body("find { it.id == '" + id + "' }.kind", equalTo("gc"))
        .body("find { it.id == '" + id + "' }.trigger", equalTo("manual"))
        .body("find { it.id == '" + id + "' }.dryRun", equalTo(false))
        .body("find { it.id == '" + id + "' }.status", equalTo("SUCCEEDED"))
        .body("find { it.id == '" + id + "' }.startedAt", notNullValue())
        .body("find { it.id == '" + id + "' }.finishedAt", notNullValue())
        .body("find { it.id == '" + id + "' }.summary", containsString("usage.before SUCCEEDED"));
  }

  @Test
  void theRunDetailCarriesEveryStepWithItsRequestAndTheAnswerItGot() {
    String id = start(true);
    assertEquals("SUCCEEDED", awaitClosed(id));

    given()
        .when()
        .get(BASE + "/runs/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("kind", equalTo("gc"))
        .body("dryRun", equalTo(true))
        .body("steps.size()", equalTo(9))
        .body("steps[0].id", equalTo("usage.before"))
        .body("steps[0].name", equalTo("Disk usage before"))
        .body("steps[0].target", equalTo("containers"))
        .body("steps[0].status", equalTo("SUCCEEDED"))
        .body("steps[0].httpStatus", equalTo(200))
        .body("steps[0].request.method", equalTo("GET"))
        .body(
            "steps[0].request.url",
            equalTo("http://qits-containers:8080/containers/api/gc/usage"))
        .body("steps[0].request.body", nullValue())
        // `response` is the peer's JSON as TEXT, stored as it arrived. A client parses it.
        .body("steps[0].response", containsString("\"sizeBytes\""))
        .body("steps[0].error", nullValue())
        .body("steps[0].summary", notNullValue())
        // The sweep is the step a dry run does not make.
        .body("steps[4].id", equalTo("artifacts.sweep"))
        .body("steps[4].status", equalTo("SKIPPED"))
        .body("steps[4].error", equalTo("dry run"))
        .body("steps[4].request", nullValue())
        .body("steps[4].dependsOn", contains("artifacts.plan"));
  }

  @Test
  void aSecondRunIsRefusedWithA409WhileOneIsActive() {
    CountDownLatch gate = peers.hold();
    try {
      String first = start(false);
      given()
          .contentType(ContentType.JSON)
          .body("{\"dryRun\":false}")
          .when()
          .post(BASE + "/processes/gc/runs")
          .then()
          .statusCode(409)
          .body("message", containsString("already active"));
      gate.countDown();
      assertEquals("SUCCEEDED", awaitClosed(first));
    } finally {
      peers.release();
    }
  }

  @Test
  void anUnknownKindIs404OnEveryRouteThatTakesOne() {
    given().when().get(BASE + "/processes/no-such/runs").then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":true}")
        .when()
        .post(BASE + "/processes/no-such/runs")
        .then()
        .statusCode(404)
        .body("message", containsString("no technical process"));
  }

  @Test
  void anUnknownRunIs404AndSoIsAnIdThatIsNotAUuid() {
    given()
        .when()
        .get(BASE + "/runs/" + java.util.UUID.randomUUID())
        .then()
        .statusCode(404)
        .body("message", containsString("no run"));
    given().when().get(BASE + "/runs/not-a-uuid").then().statusCode(404);
  }
}
