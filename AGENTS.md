# qits-platform-orchestrator — working notes

Read `README.md` first: it defines the model, lists the gc steps, the routes and the config keys.
This file is the working conventions on top of it.

## The rules that shape everything

**A process only SENDS REQUESTS.** It deletes nothing, holds no socket, opens no store but its own
run log, and makes no decision an owner has not published as an API. Every rule about what may be
deleted lives with the store's owner — qits-artifacts' GC engine, qits-containers' keep-rules. A
rule re-implemented here would be a second opinion, and it would be the copy no real deletion
exercises. If a process needs something a peer cannot do, the change goes in the peer.

**The contract is pinned by `qits-orchestrator-plan.md` in the qits-qits wrapper.** Route shapes,
step ids, the edges between them, and the request and response bodies of all four peers are written
down there and four repositories build against them in parallel. Changing one of those shapes is a
plan edit and a conversation, not a commit here.

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior `mvn
install` elsewhere, no credentials. That is why the poms duplicate versions instead of inheriting
them, why the suite spawns its own PostgreSQL from a Maven artifact, and why the peers are faked at
the client rather than stubbed with a server.

**The one thing it needs besides Maven Central** is the platform's own Maven repository, for
`qits-db-core`, `qits-auth-core` and `qits-arch-rules`. `<repositories>` in the root pom points at
`${qits.maven.repository.url}`; the image build overrides it through `.qits-maven-settings.xml`,
which mirrors the exact repository id `qits-maven` — an exact id match is what gets past Maven's
`external:http:*` blocker.

**The gate is `./mvnw clean verify -Dquarkus.http.test-port=0`**, and since the client landed it
needs BOTH a node on PATH and `git submodule update --init`. Always `clean` — incremental
compilation leaves stale generated classes behind when a shape changes. Port 0 is not optional on
the deployment host: 8081 is the platform's own npm registry there.

**Anything returned as `Response.entity(...)` is invisible to the build-time Jackson analysis**,
which is what `api/ApiWireReflection` exists for. The 202 from a started run is exactly such a
response. A new response type joins that list in the commit that adds it; the failure is a 500 in
the native binary while every JVM test stays green.

## Adding a process

One class and nothing else:

1. Implement `TechnicalProcess` as an `@ApplicationScoped` bean. `ProcessRegistry` discovers it —
   there is no list to edit, and two processes claiming one kind is a boot failure rather than a
   last-one-wins.
2. Give each step a stable `id`, a `target` from `PeerTarget`, its `dependsOn` edges, and a body
   returning a `StepResult`.
3. A body **never throws**: `StepResult.of(exchange, summary)` turns a peer's answer into the
   verdict, and `StepResult.skipped(reason)` is the other ending. The executor catches a thrown one
   anyway, but the row it writes then says less than the body could have.
4. Write the summariser beside it. It reads figures out of the peer's answer and **computes
   nothing** — a summary that re-derived what would die would be a second policy.

Its schedule, if it has one, is a class in `service/schedule` beside `GcSchedule`.

## The executor

**One thread for the whole service**, not one per kind: a run's work is other people's disks, and
two runs overlapping would be two plans against two moments of the same stores. A second run of the
same kind is refused earlier still, by `RunStore.open`, whose active-run check is **inside** the
opening transaction — a person and the cron arriving together is the ordinary case, not a race
worth losing.

**Steps run in declaration order; the edges decide only what a failure skips.** A step whose
dependencies all succeeded runs when the list reaches it, so a process reads top to bottom. A
failed step marks every transitive dependent SKIPPED, and the error names the step that actually
FAILED rather than the skipped neighbour in between — a reader should not have to walk the graph
backwards. Independent steps still run: a night where one broken peer stopped every unrelated
reclaim would be a night of no reclaim for no reason.

**A SKIPPED step does not fail a run.** A skip is the consequence of a failure already counted.

**`execute` never throws.** A thrown exception on the worker would leave a RUNNING row nothing can
close, and no second run of that kind would ever be allowed again.

## Fail-closed is an edge, not an `if`

Nothing deletes against a keep-set it could not read, and the mechanism is the dependency, not a
check inside a step. `artifacts.plan`, `artifacts.sweep` and `containers.images` depend on the pin
reads, so a failed pin read skips all three before a body runs. `GcProcess.imagesBody` would build
an empty `keep` from an unreadable pin answer — and never gets the chance to. Keep both: the edge is
the guarantee, the empty-set path is the belt.

**The rule cuts the other way too: a step with no keep-set must not wait on a pin.**
`containers.build-cache` and `containers.volumes` hang off `usage.before` alone, because a prune and
a dangling-volume sweep have nothing a pin could protect — and the build cache is the larger half of
the measured problem, so skipping it on a pin failure would cost the platform the night's biggest
reclaim for a reason that does not apply to it. Declaration order is what still runs the prune after
the image sweep; an edge would have been ordering dressed up as a requirement.

If a new step deletes on the strength of a pin, **give it the pin's edge in the same commit** — and
if it does not, **do not**.

## Outbound calls

`PeerClient` is the one way this service touches another. Two credentials on every call and they are
not alternatives: the forward-auth pair (`X-Qits-User: qits-platform-orchestrator`,
`X-Qits-Roles: qits:system`) always, and a bearer where that peer's oidc client is enabled.

**Nothing throws.** A name that does not resolve, a timeout, a body that is not JSON — each comes
back as a `PeerAnswer` carrying the sentence, because a step's job is to record what happened and a
process whose steps had to catch would put half its outcomes on a path nobody reads.

**A token that cannot be minted is empty, not an exception** — the deployer's stance. The refusal
that matters belongs to the call: an anonymous call to a guarded peer comes back 401 and the step
records the url and the status, which is more useful than a mint failure one layer earlier.

**Four named clients, one per peer**, because a token is cut FOR one service. The audience is the
one value the shipped defaults leave unset: it is environment-qualified, and an image every
environment shares must not name a tier it may not be running in. The unnamed default client is
disabled and stays disabled — the extension creates it whether or not anything injects it.

**A response is bounded at 1 MiB** with a marker appended, cut on a character boundary. An artifacts
plan lists every condemned identity on the platform; the store here is a log a person reads, and an
unbounded column would let one peer's verbosity decide this service's disk.

## Persistence

`RunStore` is the only writer. **Every write is a `DbRetry.inNewTx` ending in a flush**: `inNewTx`
owns the transaction boundary, which is the only way a retry can tell "the body threw, so it
certainly never committed" from "the transaction manager reported it" — Narayana spells a lost
commit and a real rollback with the same exception, and the flush keeps a lost connection on the
body's side of that line. These writes happen while a deletion is in flight on another host.

**Every method is `@ActivateRequestContext`**, because the caller is usually the executor's worker
thread and a Hibernate session is bound to that context. A route's call already has one, so the
annotation covers both callers.

**Steps are written as they transition**, never at the end of the run — the client polls a RUNNING
run every two seconds.

**A run that dies mid-flight keeps `finished_at` null forever**, and nothing backfills it at boot: a
successor process knows nothing about what the dead one's calls achieved.

Schema changes go in `orchestrator/src/main/resources/db/orchestrator/migration/`, hand-written, its
own lineage on its own datasource. Keep appending, never edit an applied migration. The suites run
every migration against an empty schema, so a backfill needs a test that migrates to the version
before, writes the rows the old code wrote, and migrates the rest of the way.

## Identity: two tracks, one set of roles

A request with no `Authorization` header is USER traffic — qits-gateway performed the login and
asserted `X-Qits-User` / `X-Qits-Roles`. A request WITH a bearer is MACHINE traffic, validated by
quarkus-oidc against qits-platform-idp.

**Both land as roles, which is why every route is `@RolesAllowed({"qits:admin", "qits:system"})`.**
An operator presses Run now in a browser; a machine may post the same run. There is no anonymous
route here and there must never be one — the write surface starts deletions on four other services.

`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — validation follows the rollout
gate rather than standing on its own, so with the gate off there is no OIDC tenant, nothing fetches
a JWKS, and a clone-alone build needs no issuer. There is no third state.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus merges the test resources over it rather than replacing it. Never
  re-declare an app-level setting in test resources.
- **No dev services and no containers, ever.** `EmbeddedPg` starts zonky's postgres and
  `EmbeddedPgConfigSource` hands its coordinates to every `@QuarkusTest` at an ordinal above
  `application.properties`, because the port is chosen at run time. Both are **copied** per module
  rather than shared: a test-jar dependency between two modules that have none is the higher price.
  Each module names its own database.
- **`FakePeers` is an `@Alternative` over `PeerClient.get` / `.post`** — replacing those two is
  replacing the network, at no port and no dependency, and the urls in the assertions stay the real
  ones because the inherited `url()` still resolves them from the shipped target configuration.
  `PeerClientTest` covers the other half against a loopback `HttpServer`: the headers, the 1 MiB
  bound, an unreachable peer.
- **`nextPoll()` clears the session before each poll, and it is load-bearing.** A `@QuarkusTest`
  holds ONE request context for the whole test method, so one Hibernate session serves every read
  and would answer every poll from its first-level cache — the run row would look RUNNING forever
  while the worker closed it in another session. In a deployment each poll is a fresh request.
- **The scheduler is off in the suite** (`quarkus.scheduler.enabled=false`). A cron would fire only
  at 03:40, and a suite whose outcome depends on the wall clock fails once a year for nobody's
  reason.
- A `@QuarkusTest` runs under the `test` profile, where qits-auth-core ships a dev user carrying
  `qits:admin` and `qits:system` — so the shipped `@RolesAllowed` pair is exercised rather than
  bypassed, with no `@TestSecurity` fabricating an identity no deployment produces.
- **`PackagedSurfaceIT` is the only test that runs against the artifact**, and the only place the
  identity contract is real. It hands the process `QITS_RESOURCE_DB_*` rather than restating the
  datasource keys, so the jar's own `${…}` indirection is under test, and it reads the rows back
  over JDBC. **Its peers are real calls to a dead loopback port** — the honest end-to-end proof that
  a failure reaches a readable row with none of the suite's fakes involved. ITs are skipped by
  default; `-DskipITs=false` runs against the fast-jar and `-Dnative` against the binary.

## The client

`service/src/main/webui` is the `qits-platform-spa-orchestrator` submodule (`ignore = all`,
`update = merge`, `branch = main` — the sibling shape). Quinoa 2.8.2 is pinned by hand in the root
pom, because Quinoa is in no BOM and its version does not track the platform's.

- **The segment is spelled twice**, `quarkus.quinoa.ui-root-path` here and `baseHref` in the
  submodule's `angular.json`. A mismatch serves a page whose every asset 404s and nothing on this
  side notices, so `PackagedSurfaceIT` asserts the `<base href>` string rather than the status.
- **`ignored-path-prefixes` values are RELATIVE**, matched after `ui-root-path` is stripped: `/api`
  and `/q`, never `/orchestrator/api`. An absolute value matches nothing and is indistinguishable
  from an unset key. Setting the key REPLACES Quinoa's derivation, which is why both are spelled by
  hand. **Add a literal route under `/orchestrator` and its entry here in the same commit** — and
  give it a segment of its own, because an entry protects a segment and not a string prefix.
- **The bundle is built OUTSIDE the docker build.** `@qits/ui-components` exists only on the
  platform's own npm registry, which a `RUN` reaches by no address at all. So
  `.config/qits/ci-post-receive.yml` builds it in the step container (on qits-net) and the
  Dockerfile neuters Quinoa's install/ci/build commands with `--version`, guards the staged bundle
  with a `test -f` before the multi-minute native compile, and `cp`s the bundle onto itself so
  Quinoa's MOVE does not hit overlayfs' EXDEV.
- **Quinoa is off in test mode and stays off.** Every claim about the SPA belongs in
  `PackagedSurfaceIT`.
- **`quarkus-undertow` must never be on the classpath.** It arrives transitively from anything
  servlet-shaped and takes over the static-resource route Quinoa serves the bundle through — a
  packaged process that answers the API correctly and the SPA with a 404.

      ./mvnw -pl service -am dependency:tree | grep -i undertow

## Deliberately not here yet

Each is a decision, not an omission:

- **Cancelling a run.** There is no route and no column for it. A step is one HTTP call to a peer
  that is already deleting; interrupting the wait would abandon the answer rather than the work, and
  a run row saying CANCELLED would be a claim this service cannot make.
- **Events and causation.** No `qits-eventstream` dependency. Nothing subscribes to a run today and
  a publisher would arrive with the vocabulary jar every announcing service has.
- **A size budget for the artifacts GC.** L1.4 of the storage plan; it is a policy the artifacts
  engine has to hold, not a number this service could pass.
- **OpenTelemetry export.** The siblings ship `quarkus-opentelemetry` with the four preview keys
  spelled out; adding it is the extension plus that block.
- **A committed `docs/openapi.yml`.** The document is served at `/orchestrator/q/openapi`.
