package eu.wohlben.qits.orchestrator.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply per datasource: {@code jdbc.url}, {@code username}, {@code
 * password}.
 *
 * <p>It is a config source rather than lines in {@code src/test/resources/application.properties}
 * because the port is chosen at run time — the instance takes a free one, so nothing can be written
 * down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both jars' shipped
 * defaults and anything the test properties file might carry, and it is registered through {@code
 * META-INF/services}, which is how a config source joins a Quarkus application without being a bean.
 *
 * <p>It supplies the DATASOURCE keys rather than the {@code QITS_RESOURCE_*} triples the shipped
 * defaults expand: the packaged-artifact IT in this module takes the triples, because there the
 * point is to exercise the shipped expression itself. Here the point is only to have a database.
 *
 * <p><b>Two databases on one server, as in production.</b> Their names differ from the {@code
 * orchestrator} module's own suite database, so no two modules ever mean the same schema.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance — the other module names its own. */
  private static final String DATABASE = "orchestrator_svc";

  /**
   * The event bus's outbox and claim tables.
   *
   * <p>It is here because joining the qits-eventstream jar turned this deployable into one that
   * opens a second datasource: {@code qits.eventstream.enabled=false} under {@code %test} stops
   * publishing, sweeping and dialling, and stops none of the connecting and migrating Quarkus does
   * at boot. So the outbox gets a database here or the whole suite fails to start.
   */
  private static final String EVENTSTREAM_DATABASE = "orchestrator_svc_eventstream";

  private static final String PREFIX = "quarkus.datasource.orchestrator.";

  private static final String EVENTSTREAM_PREFIX = "quarkus.datasource.eventstream.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD,
          EVENTSTREAM_PREFIX + "jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          EVENTSTREAM_PREFIX + "username", EmbeddedPg.USER,
          EVENTSTREAM_PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
