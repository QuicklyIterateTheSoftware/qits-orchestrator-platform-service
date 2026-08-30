package eu.wohlben.qits.orchestrator.stories.support;

/**
 * The one launched process, addressed the way every one of its surfaces is addressed — and named
 * the way a diagram names it.
 *
 * <p>{@code quarkus.rest.path=/orchestrator/api} is the JSON API and {@code
 * quarkus.http.non-application-root-path=/orchestrator/q} is what Quarkus itself serves, so the
 * framework's shipped RestAssured tap — which skips any path carrying a {@code /q/} <i>segment</i>
 * rather than a leading one — is exactly right here and no story class overrides the predicate. A
 * story's readiness probe therefore draws nothing, which is what keeps the diagrams about the
 * deletion run.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths, and RestAssured is
 * handed the port by the Quarkus integration-test extension.
 *
 * <p><b>Every kind, name and repository a story uses is a stable literal</b>, never a run stamp.
 * {@link eu.wohlben.qits.userflows.Labels} rewrites only path segments it can tell were generated
 * (a uuid, a long hex run, a bare number); {@code gc} and {@code not-a-uuid} are none of those and
 * survive into a label exactly as written. The run ids <b>are</b> uuids and are rewritten, which is
 * the point: {@code GET /orchestrator/api/runs/{id}} is template-shaped because a run id is
 * generated.
 *
 * <p><b>A query string never reaches a label from the shipped tap.</b> It labels {@code METHOD
 * <scrubbed PATH> -> <status>} and drops the query entirely, so the run listing's {@code ?limit=10}
 * is invisible to the diagram. The corollary is the trap: two routes differing only in their query
 * are ONE edge.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-platform-orchestrator";

  /** {@code /orchestrator/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/orchestrator/api";

  /** The process catalogue: the plan four repositories build against, steps and edges included. */
  public static final String PROCESSES_PATH = API_PATH + "/processes";

  /** The one kind this platform has. It is in every url and in every stored row. */
  public static final String GC = "gc";

  /** A process's runs: the history on GET, the start on POST. */
  public static final String GC_RUNS_PATH = PROCESSES_PATH + "/" + GC + "/runs";

  /** One run with its steps — its own root, because a run id is unique on its own. */
  public static final String RUNS_PATH = API_PATH + "/runs";

  /** The label every read of one run renders as: a run id is a uuid, so it scrubs to {@code {id}}. */
  public static final String RUN_LABEL_PATH = RUNS_PATH + "/{id}";

  /** The kind no process claims — what a typo looks like on the wire. */
  public static final String UNKNOWN_KIND = "no-such-process";

  /** A run id that is not a uuid at all. Authored, not generated, so it survives scrubbing. */
  public static final String MALFORMED_RUN_ID = "not-a-uuid";

  private StoryTarget() {}

  /** The address of one run — what a 202 hands back and what an operator pastes into a message. */
  public static String runPath(String id) {
    return RUNS_PATH + "/" + id;
  }

  /** The request body of a start. One flag, and a missing body would be a real run. */
  public static String startBody(boolean dryRun) {
    return "{\"dryRun\":" + dryRun + "}";
  }
}
