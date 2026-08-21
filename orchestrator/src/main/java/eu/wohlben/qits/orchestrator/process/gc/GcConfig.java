package eu.wohlben.qits.orchestrator.process.gc;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The gc run's policy: how old is old enough, how much cache to keep, and what is never a
 * candidate.
 *
 * <p><b>Policy here, rules with the owner.</b> Nothing on this class decides WHETHER something may
 * be deleted — that is qits-artifacts' engine and qits-containers' keep-rules, each beside the
 * store it owns. What lives here are the numbers a platform tunes: they travel into a request body
 * and the owner applies them.
 */
@ApplicationScoped
public class GcConfig {

  @ConfigProperty(name = "qits.orchestrator.gc.enabled")
  boolean enabled;

  @ConfigProperty(name = "qits.orchestrator.gc.dry-run")
  boolean scheduledDryRun;

  @ConfigProperty(name = "qits.orchestrator.gc.image-keep-prefixes")
  List<String> imageKeepPrefixes;

  @ConfigProperty(name = "qits.orchestrator.gc.image-min-age")
  Duration imageMinAge;

  @ConfigProperty(name = "qits.orchestrator.gc.volume-min-age")
  Duration volumeMinAge;

  @ConfigProperty(name = "qits.orchestrator.gc.build-cache-keep-bytes")
  long buildCacheKeepBytes;

  /** Whether the CLOCK may start a run. A manual run ignores this — a person is the trigger. */
  public boolean enabled() {
    return enabled;
  }

  /** Whether the SCHEDULED run deletes. A manual run carries its own flag from the request. */
  public boolean scheduledDryRun() {
    return scheduledDryRun;
  }

  /** Image tag prefixes qits-containers must keep whatever its own rules say. */
  public List<String> imageKeepPrefixes() {
    return List.copyOf(imageKeepPrefixes);
  }

  /** How young an image is protected from the age rule — the build-then-push window. */
  public Duration imageMinAge() {
    return imageMinAge;
  }

  /** How young a dangling volume is protected — the stop-then-start window. */
  public Duration volumeMinAge() {
    return volumeMinAge;
  }

  /** What buildkit may keep after a prune, host builder and buildx containers alike. */
  public long buildCacheKeepBytes() {
    return buildCacheKeepBytes;
  }
}
