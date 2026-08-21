package eu.wohlben.qits.orchestrator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One step of one run: the request that went out and the answer that came back.
 *
 * <p><b>Every transition is persisted as it happens</b>, not at the end of the run. The UI polls
 * {@code GET /runs/{id}} every two seconds while a run is RUNNING, and a run that wrote its steps
 * at the end would show nine PENDING cards for several minutes and then everything at once.
 *
 * <p><b>The row's {@link #id} is storage and never a wire identifier.</b> What the API reports as a
 * step's {@code id} is {@link #stepId} — the stable id in the process definition, which is also
 * what {@link #dependsOn} names.
 */
@Entity
@Table(name = "op_step")
public class OpStep extends PanacheEntityBase {

  /** How much of a peer's answer is kept. See {@code RunExecutor} for what happens past it. */
  public static final int RESPONSE_LIMIT_BYTES = 1024 * 1024;

  @Id public UUID id;

  @Column(name = "run_id", nullable = false)
  public UUID runId;

  /** Declaration order, from 0 — the order the steps ran in. */
  @Column(nullable = false)
  public int seq;

  /** The step's stable id in the process definition, e.g. {@code containers.images}. */
  @Column(name = "step_id", nullable = false, length = 128)
  public String stepId;

  @Column(nullable = false, length = 255)
  public String name;

  /** The peer this step calls: {@code artifacts}, {@code containers}, {@code ci} or {@code deployments}. */
  @Column(nullable = false, length = 64)
  public String target;

  /**
   * The step ids this one waits for, comma-separated, copied from the definition at run start. A
   * run stays readable after the definition changes because the edges are in the row.
   */
  @Column(name = "depends_on", columnDefinition = "text")
  public String dependsOn;

  /** PENDING, RUNNING, SUCCEEDED, FAILED or SKIPPED — {@code RunStatus}'s names. */
  @Column(nullable = false, length = 32)
  public String status;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /** The peer's status code, or null when the call never got one. */
  @Column(name = "http_status")
  public Integer httpStatus;

  @Column(name = "request_method", length = 16)
  public String requestMethod;

  @Column(name = "request_url", columnDefinition = "text")
  public String requestUrl;

  /**
   * The request body as it went out, whole. It carries the keep-set this run computed, which is what
   * "why was that image deleted" is answered from.
   */
  @Column(name = "request_body", columnDefinition = "text")
  public String requestBody;

  /** The peer's answer, bounded at {@link #RESPONSE_LIMIT_BYTES} with a marker appended. */
  @Column(name = "response_body", columnDefinition = "text")
  public String responseBody;

  /** Why it failed, or why it was skipped. */
  @Column(columnDefinition = "text")
  public String error;

  /** The step in one human line, computed from the response by the step that made the call. */
  @Column(columnDefinition = "text")
  public String summary;
}
