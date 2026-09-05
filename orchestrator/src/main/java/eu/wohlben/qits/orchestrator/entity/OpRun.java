package eu.wohlben.qits.orchestrator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One execution of one technical process.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom.
 *
 * <p><b>A run is a log entry, not a projection.</b> Everything on it is written as the run happens
 * and never recomputed from a peer afterwards — by the time anyone reads it, the stores it acted on
 * have moved on. That is the whole reason this table exists rather than a page that asks the peers
 * what they hold now.
 */
@Entity
@Table(name = "op_run")
public class OpRun extends PanacheEntityBase {

  @Id public UUID id;

  /** The process kind — {@code gc} and nothing else in v1. See {@code ProcessRegistry}. */
  @Column(nullable = false, length = 64)
  public String kind;

  /**
   * {@code manual}, {@code scheduled} or {@code event} — {@code RunTrigger}'s wire spelling. The
   * column is named {@code trigger}; PostgreSQL treats the word as non-reserved, so it needs no
   * quoting, and it is a varchar rather than an enum type precisely so a new value is a code change
   * and not a migration.
   */
  @Column(name = "trigger", nullable = false, length = 32)
  public String trigger;

  /**
   * Whether this run was allowed to delete. It travels into every peer request as that peer's own
   * {@code dryRun} flag, so a dry run still calls everything and reports real figures.
   */
  @Column(name = "dry_run", nullable = false)
  public boolean dryRun;

  /** RUNNING, SUCCEEDED or FAILED — {@code RunStatus}'s names. */
  @Column(nullable = false, length = 32)
  public String status;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  /**
   * Null while the run is RUNNING, and null forever for a run whose process died mid-flight. That
   * is deliberate: a successor process knows nothing about what the dead one's calls achieved, so
   * fabricating an ending would be worse than an honest gap.
   */
  @Column(name = "finished_at")
  public Instant finishedAt;

  /** The run in one human line, written when it ends. */
  @Column(columnDefinition = "text")
  public String summary;
}
