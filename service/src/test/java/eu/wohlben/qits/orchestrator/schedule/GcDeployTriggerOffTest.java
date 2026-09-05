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
 * {@code qits.orchestrator.gc.deploy-trigger.enabled=false}: deployments stop being a trigger, and
 * nothing else changes.
 *
 * <p>The claim worth making is the narrow one — <b>nothing is armed</b>. The listener still consumes
 * and settles its events (a consumer that stopped claiming would owe every one of them forever, and
 * a later re-enable would then be handed the backlog), so the only thing this key turns off is the
 * timer, which is exactly what is asserted.
 */
@QuarkusTest
@TestProfile(GcDeployTriggerOffTest.DeployTriggerOff.class)
class GcDeployTriggerOffTest {

  public static class DeployTriggerOff implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.orchestrator.gc.deploy-trigger.enabled", "false");
    }
  }

  @Inject GcDeployTrigger trigger;

  @Inject ManualDeployTriggerTimer timer;

  @Test
  void aDeploymentArmsNothing() {
    timer.reset();

    trigger.onDeploymentActive("qits-artifacts", "2026.905.62255", "dev");
    trigger.onDeploymentActive("qits-containers", "2026.905.62255", "dev");

    assertEquals(List.of(), timer.armedDelays());
    assertFalse(timer.isArmed());
  }
}
