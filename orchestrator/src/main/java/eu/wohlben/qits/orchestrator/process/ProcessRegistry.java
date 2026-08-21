package eu.wohlben.qits.orchestrator.process;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every technical process this service knows, by kind.
 *
 * <p>CDI discovery rather than a hand-written list: adding a process is one class and its registry
 * entry is the {@code @ApplicationScoped} on it. Ordering is by kind so the listing is stable — a
 * page whose rows move between two reads is a page nobody trusts.
 *
 * <p><b>Two kinds with the same name is a boot failure</b>, not a last-one-wins. The kind is in
 * every url and in every stored row; two processes answering to one is a run whose record cannot be
 * read back.
 */
@ApplicationScoped
public class ProcessRegistry {

  @Inject Instance<TechnicalProcess> discovered;

  private final Map<String, TechnicalProcess> byKind = new LinkedHashMap<>();

  @PostConstruct
  void index() {
    discovered.stream()
        .sorted((left, right) -> left.kind().compareTo(right.kind()))
        .forEach(
            process -> {
              TechnicalProcess clash = byKind.putIfAbsent(process.kind(), process);
              if (clash != null) {
                throw new IllegalStateException(
                    "two technical processes claim the kind '"
                        + process.kind()
                        + "': "
                        + clash.getClass().getName()
                        + " and "
                        + process.getClass().getName());
              }
            });
  }

  /** Every process, by kind. */
  public List<TechnicalProcess> all() {
    return List.copyOf(byKind.values());
  }

  /** One process, or empty — which the route turns into a 404. */
  public Optional<TechnicalProcess> byKind(String kind) {
    return Optional.ofNullable(byKind.get(kind));
  }
}
