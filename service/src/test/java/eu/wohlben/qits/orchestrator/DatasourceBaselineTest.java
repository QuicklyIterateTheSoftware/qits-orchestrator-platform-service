package eu.wohlben.qits.orchestrator;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import org.junit.jupiter.api.Test;

/**
 * The `orchestrator` datasource carries the platform's resilience baseline: the patient driver,
 * validation at borrow, and a 15s acquisition timeout. The rule reads the config rather than the
 * code, and it names each missing line.
 *
 * <p>It lives in {@code service/} because this module's classpath is the deployable's whole config —
 * the datasource itself is declared in the {@code orchestrator} jar, and a service that adds a
 * second one is judged here without anything being added to this class.
 *
 * <p>This service writes the only account of a deletion that exists, and it writes it while the
 * deletion is in flight on another host. A pool that hands out a dead connection during a postgres
 * cutover would lose exactly that.
 */
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
