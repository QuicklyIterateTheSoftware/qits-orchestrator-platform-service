package eu.wohlben.qits.orchestrator.process.gc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * The one human line each gc step leaves behind, read out of the peer's own answer.
 *
 * <p><b>Nothing here is computed twice.</b> Every figure is taken from the document that reports
 * it — a summary that re-derived "what would die" would be a second policy, and two policies in one
 * report is the mistake the whole design refuses. qits-artifacts' own {@code GcSummary} says the
 * same thing about the same numbers.
 *
 * <p><b>Every reader tolerates a missing field.</b> A peer that answered 200 with a shape this does
 * not recognise is a SUCCEEDED step with a thin caption, never a failed deletion: the response body
 * is stored whole beside it, so nothing is lost by a summary that cannot read it.
 */
final class GcSummaries {

  private GcSummaries() {}

  /** {@code images 43.5 GB (19.3 GB reclaimable), build cache 35.1 GB} */
  static String usage(JsonNode body) {
    if (body == null) {
      return "no usage figures in the answer";
    }
    JsonNode images = body.path("images");
    JsonNode cache = body.path("buildCache");
    return "images "
        + bytes(images.path("sizeBytes").asLong())
        + " ("
        + bytes(images.path("reclaimableBytes").asLong())
        + " reclaimable), build cache "
        + bytes(cache.path("sizeBytes").asLong());
  }

  /** {@code 14 applications pinned} — the deployments pin answer. */
  static String deploymentPins(JsonNode body) {
    int applications = body == null ? 0 : body.path("pins").size();
    return applications + (applications == 1 ? " application pinned" : " applications pinned");
  }

  /** {@code qits-ci-daemon 2026.815.120000 (previous 2026.814.101010)} — the ci pin answer. */
  static String ciPin(JsonNode body) {
    if (body == null) {
      return "no daemon pin in the answer";
    }
    String name = text(body, "daemonName", "the ci daemon");
    String version = text(body, "daemonVersion", "");
    String previous = text(body, "previousDaemonVersion", "");
    if (version.isBlank()) {
      return name + " pins nothing";
    }
    return name + " " + version + (previous.isBlank() ? "" : " (previous " + previous + ")");
  }

  /** {@code 128 identities, 19.3 GB reclaimable, executable=true} — the artifacts plan answer. */
  static String artifactsPlan(JsonNode body) {
    JsonNode summary = body == null ? null : body.get("summary");
    if (summary == null) {
      return "no plan summary in the answer";
    }
    return summary.path("identitiesCondemned").asInt()
        + " identities, "
        + text(summary, "reclaimable", bytes(summary.path("reclaimableBytes").asLong()))
        + " reclaimable, executable="
        + summary.path("executable").asBoolean();
  }

  /** {@code 91 blobs unlinked, 17.8 GB reclaimed} — the artifacts sweep answer. */
  static String artifactsSweep(JsonNode body) {
    JsonNode sweep = body == null ? null : body.get("sweep");
    if (sweep == null) {
      String aborted = body == null ? null : text(body, "aborted", "");
      return aborted == null || aborted.isBlank()
          ? "no sweep outcome in the answer"
          : "aborted: " + aborted;
    }
    return sweep.path("blobsUnlinked").asInt()
        + " blobs unlinked, "
        + bytes(sweep.path("bytesReclaimed").asLong())
        + " reclaimed";
  }

  /** {@code 12 images removed, 9.4 GB reclaimed, 61 kept} */
  static String images(JsonNode body) {
    if (body == null) {
      return "no image figures in the answer";
    }
    return body.path("removed").size()
        + " images removed, "
        + bytes(body.path("bytesReclaimed").asLong())
        + " reclaimed, "
        + body.path("kept").size()
        + " kept";
  }

  /** {@code 4 volumes removed, 9 kept} — no bytes, because the peer reports none. */
  static String volumes(JsonNode body) {
    if (body == null) {
      return "no volume figures in the answer";
    }
    return body.path("removed").size()
        + " volumes removed, "
        + body.path("kept").size()
        + " kept";
  }

  /** {@code host 12.6 GB reclaimed, 2 builders 3.1 GB reclaimed} */
  static String buildCache(JsonNode body) {
    if (body == null) {
      return "no build cache figures in the answer";
    }
    long host = body.path("host").path("reclaimedBytes").asLong();
    JsonNode builders = body.path("builders");
    long fromBuilders = 0;
    for (JsonNode builder : builders) {
      fromBuilders += builder.path("reclaimedBytes").asLong();
    }
    return "host "
        + bytes(host)
        + " reclaimed, "
        + builders.size()
        + (builders.size() == 1 ? " builder " : " builders ")
        + bytes(fromBuilders)
        + " reclaimed";
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  /**
   * Bytes as a person reads them. Powers of 1000 with the SI names, because that is what {@code
   * docker system df} prints and a report that disagreed with the command it summarises would be
   * read as wrong.
   */
  static String bytes(long value) {
    if (value < 1000) {
      return value + " B";
    }
    String[] units = {"kB", "MB", "GB", "TB", "PB"};
    double scaled = value;
    int unit = -1;
    while (scaled >= 1000 && unit < units.length - 1) {
      scaled /= 1000;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
  }
}
