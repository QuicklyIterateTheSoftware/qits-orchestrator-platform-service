package eu.wohlben.qits.orchestrator.schedule;

import java.time.Duration;

/**
 * Where a debounced gc run waits out its quiet period.
 *
 * <p><b>One method, and it is an interface for one reason: the suite has to be able to drive the
 * delay rather than sleep through it.</b> A trailing-edge debounce is entirely a claim about WHEN —
 * that five events in a minute produce one run and not five, and that a late event pushes the
 * pending one back rather than starting a second — and a test that proves either by waiting proves
 * it on the machine it ran on. Shortening the quiet period to milliseconds does not fix that: it
 * makes the assertions depend on which of two threads a loaded CI box scheduled first.
 *
 * <p>{@code ScheduledDeployTriggerTimer} is the real one, a single daemon thread. The suite's
 * {@code ManualDeployTriggerTimer} is an {@code @Alternative} over it, exactly as {@code FakePeers}
 * is over {@code PeerClient}.
 */
public interface DeployTriggerTimer {

  /**
   * Parks {@code task} for {@code delay}.
   *
   * @return the handle that cancels it, if it has not run yet
   */
  Pending after(Duration delay, Runnable task);

  /** A parked task. Cancelling one that has already run is a no-op, never an error. */
  @FunctionalInterface
  interface Pending {
    void cancel();
  }
}
