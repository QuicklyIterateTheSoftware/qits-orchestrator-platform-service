package eu.wohlben.qits.orchestrator.schedule;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The real timer: one daemon thread that holds nothing but a pending gc run.
 *
 * <p><b>Deliberately not quarkus-scheduler.</b> {@code @Scheduled} is a fixed cadence — the cron
 * this trigger sits beside — and what is wanted here is a single deadline that keeps being pushed
 * back. A one-shot schedule with a cancel is the whole primitive.
 *
 * <p>The thread is a daemon with a name, for the reasons {@code RunExecutor}'s worker is: a stuck
 * task is identifiable in a thread dump, and a JVM shutting down is not held open by a run that is
 * still ten minutes away from starting. There is no graceful drain on shutdown either, and that is
 * the right answer rather than a shortcut — the parked task has not started a run, has written no
 * row and owes nothing; the successor process hears the next deployment, and the cron is the
 * backstop regardless.
 */
@ApplicationScoped
public class ScheduledDeployTriggerTimer implements DeployTriggerTimer {

  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "qits-orchestrator-deploy-trigger");
            thread.setDaemon(true);
            return thread;
          });

  void onShutdown(@Observes ShutdownEvent event) {
    timer.shutdownNow();
  }

  @Override
  public Pending after(Duration delay, Runnable task) {
    // A negative or zero configured quiet period means "as soon as the wave stops", not "never".
    long millis = Math.max(0L, delay.toMillis());
    ScheduledFuture<?> future = timer.schedule(task, millis, TimeUnit.MILLISECONDS);
    // false: a task already running is a run already opening, and interrupting that would leave the
    // row half written. Cancelling only ever un-parks something that has not begun.
    return () -> future.cancel(false);
  }
}
