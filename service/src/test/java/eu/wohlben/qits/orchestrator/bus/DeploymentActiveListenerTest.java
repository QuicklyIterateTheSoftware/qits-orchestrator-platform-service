package eu.wohlben.qits.orchestrator.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.orchestrator.schedule.ManualDeployTriggerTimer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The seam: what arrives on the bus becomes an armed gc run, and what cannot be read becomes
 * nothing at all.
 *
 * <p>This class drives {@link DeploymentActiveListener#onFrame} directly rather than through the
 * library's dispatcher, which is the honest scope: the funnel, the claim and the watermark are
 * qits-eventstream's and have their own suite over there. What is this repository's is the decode
 * and the handover — and the rule that <b>neither may ever throw</b>, because {@code onFrame} runs
 * inside the transaction that claims the event and a throw rolls the claim back, leaving the event
 * owed forever.
 *
 * <p>The armed run is observed through {@link ManualDeployTriggerTimer}, so nothing here waits for a
 * quiet period and no gc run is actually started.
 */
@QuarkusTest
class DeploymentActiveListenerTest {

  @Inject DeploymentActiveListener listener;

  @Inject ManualDeployTriggerTimer timer;

  @BeforeEach
  void quiet() {
    timer.reset();
  }

  /** A frame as the stream delivers one: the envelope's fields plus the log row's id. */
  private static EventFrame frame(String payload) {
    return new EventFrame(
        "4e0f2b4a-7c1e-4f0a-9a1d-1f2b3c4d5e6f",
        "DeploymentActive",
        Instant.parse("2026-09-05T08:47:00Z"),
        payload,
        "qits-artifacts 2026.905.62255 is live in dev",
        null,
        "dev");
  }

  /** The wire spelling, and the vocabulary this consumer asked for. */
  @Test
  void itSubscribesToDeploymentActiveUnderAStableConsumerId() {
    assertEquals("orchestrator-gc-on-deploy", listener.consumerId());
    assertEquals(Set.of("DeploymentActive"), listener.signatures());
    // The head of the log, not the epoch: replaying history would arm a collection for every
    // deployment this platform has ever made.
    assertFalse(listener.replayFromEpoch());
  }

  /**
   * A REAL payload, carrying every field qits-deployments actually sends — including the six this
   * consumer has no use for. Binding must ignore them (the library's mapper has
   * FAIL_ON_UNKNOWN_PROPERTIES off), which is the whole reason a local record is a supported answer
   * to a vocabulary jar that is not published.
   */
  @Test
  void aRepresentativeDeploymentArmsARun() {
    String payload =
        """
        {"applicationName":"qits-artifacts",\
        "commitSha":"8809feb1c0de",\
        "containerName":"dev-qits-artifacts",\
        "deploymentId":"6b1a0f5c-2d3e-4f50-8a9b-0c1d2e3f4a5b",\
        "endpoints":["http://dev-qits-artifacts:8080"],\
        "environmentId":"1f2e3d4c-5b6a-4798-8877-665544332211",\
        "environmentName":"dev",\
        "navigation":null,\
        "version":"2026.905.62255"}""";

    listener.onFrame(frame(payload));

    assertTrue(timer.isArmed(), "a live deployment armed no gc run");
    assertEquals(1, timer.armedDelays().size());
  }

  /** The three fields the log line is made of are the three that bind. */
  @Test
  void theLocalRecordBindsTheThreeFieldsItNames() {
    DeploymentActiveListener.DeploymentActivePayload bound =
        CanonicalJson.payloadTo(
            "{\"applicationName\":\"qits-ci\",\"version\":\"2026.905.1\","
                + "\"environmentName\":\"dev\",\"deploymentId\":\"ignored\"}",
            DeploymentActiveListener.DeploymentActivePayload.class);

    assertEquals("qits-ci", bound.applicationName());
    assertEquals("2026.905.1", bound.version());
    assertEquals("dev", bound.environmentName());
  }

  /**
   * Poison: the same bytes would fail identically on every later offer, so the event is SETTLED with
   * a warning rather than thrown back at the funnel. A throw here would hold this consumer's
   * watermark behind one unreadable frame and stop every later deployment from being read.
   */
  @Test
  void anUnreadablePayloadIsSettledRatherThanThrown() {
    for (String poison : List.of("not json at all", "[1,2,3]", "\"a bare string\"", "")) {
      assertDoesNotThrow(() -> listener.onFrame(frame(poison)), poison);
      assertFalse(timer.isArmed(), "an unreadable payload armed a run: " + poison);
    }
    assertEquals(List.of(), timer.armedDelays());
  }
}
