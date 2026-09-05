package eu.wohlben.qits.orchestrator.schedule;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The quiet period, driven by hand.
 *
 * <p><b>An {@code @Alternative} over the one seam the debounce schedules through</b>, exactly as
 * {@link eu.wohlben.qits.orchestrator.peer.FakePeers} is over {@code PeerClient}: replacing {@code
 * after} is replacing the clock, at no thread and no sleep. What a debounce claims is entirely about
 * WHEN — five events in a minute make one run and not five, a late event moves the pending one
 * rather than adding a second — and every one of those assertions is a race if the test has to wait
 * for a real delay to lapse.
 *
 * <p>It is enabled for the whole suite, which is also what keeps a stray event in some other test
 * from ever starting a run: nothing fires here unless a test calls {@link #elapse()}.
 *
 * <p><b>Everything is read through a METHOD, never a public field.</b> This is a normal-scoped bean,
 * so a test injects a client proxy — and a proxy forwards method calls and does not forward field
 * access. A public {@code armed} list would read the proxy's own empty one and every assertion about
 * the debounce would pass by being vacuous.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ManualDeployTriggerTimer implements DeployTriggerTimer {

  private final List<Duration> armed = new CopyOnWriteArrayList<>();

  private final AtomicInteger cancellations = new AtomicInteger();

  private final AtomicReference<Runnable> parked = new AtomicReference<>();

  public void reset() {
    armed.clear();
    cancellations.set(0);
    parked.set(null);
  }

  /** Every delay armed, in order — so a test can assert the configured quiet period was used. */
  public List<Duration> armedDelays() {
    return List.copyOf(armed);
  }

  /**
   * How many parked tasks were actually un-parked: the push-backs.
   *
   * <p><b>Counted on effect rather than on the call</b>, exactly as {@code ScheduledFuture#cancel}
   * reports it. The trigger is an application-scoped bean that outlives a test method, so it may
   * still hold the handle a previous method left it; cancelling that handle un-parks nothing and
   * must not be counted, or every method's expected figure would depend on which ran before it.
   */
  public int cancellations() {
    return cancellations.get();
  }

  @Override
  public Pending after(Duration delay, Runnable task) {
    armed.add(delay);
    parked.set(task);
    // Only un-park THIS task: a cancel arriving after something else was parked, or after this one
    // already ran, must not empty the slot — the same rule ScheduledFuture#cancel has — and it is
    // not a push-back either, so it is not counted.
    return () -> {
      if (parked.compareAndSet(task, null)) {
        cancellations.incrementAndGet();
      }
    };
  }

  /** Whether a run is currently parked. */
  public boolean isArmed() {
    return parked.get() != null;
  }

  /**
   * The quiet period lapses: whatever is parked runs, on the calling thread.
   *
   * <p>The slot is cleared BEFORE the task runs, because the task itself may park a new one — that
   * is exactly what the busy-executor arm does.
   */
  public void elapse() {
    Runnable task = parked.getAndSet(null);
    if (task == null) {
      throw new AssertionError("nothing is armed");
    }
    task.run();
  }
}
