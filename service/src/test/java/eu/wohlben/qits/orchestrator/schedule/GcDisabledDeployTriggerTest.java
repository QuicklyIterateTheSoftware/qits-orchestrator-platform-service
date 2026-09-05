package eu.wohlben.qits.orchestrator.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.orchestrator.gc.enabled=false}: the whole unattended process is off, so a deployment
 * arms nothing either — the key that stops the clock stops the bus trigger with it.
 *
 * <p>Its own class rather than a second case in {@link GcDeployTriggerOffTest} because a {@code
 * @TestProfile} is one application, and the two gates are two different questions that must both be
 * closed on their own: the trigger's key says whether deployments cause a run, and this one says
 * whether an unattended run may happen at all. A test that set both would prove neither.
 *
 * <p>The same key already gates {@code GcSchedule}, and that half has no test in this repository —
 * see the note in {@code stories/support/StoryProfile}. This one is testable because the trigger's
 * timer is a seam and the cron's is quarkus-scheduler's.
 */
@QuarkusTest
@TestProfile(GcDisabledDeployTriggerTest.GcDisabled.class)
class GcDisabledDeployTriggerTest {

  public static class GcDisabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.orchestrator.gc.enabled", "false");
    }
  }

  @Inject GcDeployTrigger trigger;

  @Inject ManualDeployTriggerTimer timer;

  @Test
  void aDeploymentArmsNothingWhenTheProcessIsDisabled() {
    timer.reset();

    trigger.onDeploymentActive("qits-artifacts", "2026.905.62255", "dev");

    assertEquals(List.of(), timer.armedDelays());
    assertFalse(timer.isArmed());
  }
}
