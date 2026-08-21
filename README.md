# qits-platform-orchestrator

**Technical processes**: multi-step jobs that only send requests to other services and record what
happened. One process exists, `gc` — the platform's unified deletion run.

It deletes nothing itself and holds no docker socket. Registry blobs, tags and manifests are
qits-artifacts' own GC engine; host images, orphan volumes and buildkit cache are qits-containers',
which is the component that holds the socket. This service reads the platform's pin set once, hands
it to every deleter, calls each owner's API in dependency order, and keeps the account.

Naming note: qits-containers' own docs call it "the container orchestrator". That word has two
meanings on the platform now; the containers repo keeps its prose, this service is "the
orchestrator" in navigation and in conversation.

The contract — routes, step ids, edges, request and response shapes — is pinned by
`qits-orchestrator-plan.md` in the qits-qits wrapper. Four repositories build against it.

## The gc process

Steps run in declaration order. An edge is a requirement, not an ordering hint: a failed step marks
every transitive dependent SKIPPED, while independent steps still run.

| id | target | call | depends on |
|---|---|---|---|
| `usage.before` | containers | `GET /containers/api/gc/usage` | — |
| `pins.deployments` | deployments | `GET /platform-deployments/api/pins` | — |
| `pins.ci` | ci | `GET /ci/api/daemon` | — |
| `artifacts.plan` | artifacts | `POST /artifacts/api/gc/plan {pins}` | pins.deployments, pins.ci |
| `artifacts.sweep` | artifacts | `POST /artifacts/api/gc/sweep {pins}` — SKIPPED `dry run` on a dry run | artifacts.plan |
| `containers.images` | containers | `POST /containers/api/gc/images` | pins.deployments |
| `containers.volumes` | containers | `POST /containers/api/gc/volumes` | usage.before |
| `containers.build-cache` | containers | `POST /containers/api/gc/build-cache` | usage.before |
| `usage.after` | containers | `GET /containers/api/gc/usage` | artifacts.sweep, containers.images, containers.volumes, containers.build-cache |

**The pins are read once and handed on.** `artifacts.plan` and `artifacts.sweep` send

```json
{"pins": {"deployments": <the deployments answer, verbatim>,
          "ciDaemon":    <the ci answer, verbatim>}}
```

qits-artifacts uses these instead of its own HTTP readers; a missing member means that source is
unanswered, which makes the plan not executable and aborts a sweep. The readers moved here because
this is the one component holding an idp client for every peer — theirs had no credential and were
401-ing.

**Only what needs a pin waits for one.** `containers.build-cache` hangs off `usage.before`, not
off the image sweep: a prune has no keep-set, so a broken pin read must not cost the platform the
largest reclaim of the night. Declaration order still runs it after the image sweep.

**The image keep-set** is every deployment pin as a local tag, `qits/<applicationName>:<sha>`, plus
`qits.orchestrator.gc.image-keep-prefixes`. Fail-closed is the edge: a pin read that failed skips
the image sweep, so an empty keep-set can never reach qits-containers.

**Dry run** still calls every peer — each one plans instead of deleting — so its figures are real
figures. Only `artifacts.sweep` is skipped outright, because qits-artifacts' sweep has no dry mode.

## API

Under `/orchestrator/api`. Every route takes `qits:admin` (a person, via qits-gateway's
`X-Qits-User` / `X-Qits-Roles`) or `qits:system` (a machine, via a bearer). There is no anonymous
route.

```
GET  /processes                      → [{kind, name, description,
                                         steps:[{id, name, target, dependsOn[]}]}]
GET  /processes/{kind}/runs?limit=20 → [{id, kind, trigger, dryRun, status,
                                         startedAt, finishedAt, summary}]
POST /processes/{kind}/runs {dryRun} → 202 {id}   404 unknown kind   409 one is active
GET  /runs/{id}                      → the run above, plus
                                        steps:[{id, name, target, dependsOn[], status,
                                                startedAt, finishedAt, httpStatus,
                                                request:{method, url, body},
                                                response, error, summary}]
```

- `status` is `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED` or `SKIPPED`. A run is only `RUNNING`,
  `SUCCEEDED` or `FAILED`, and it is `FAILED` if any step is — a skip does not fail it.
- `trigger` is `manual` or `scheduled`.
- **`request.body` and `response` are STRINGS carrying JSON text**, not embedded objects. A
  response is stored as the bytes that arrived, bounded at 1 MiB with a truncation marker past it —
  and a truncated document is not parseable JSON, so it could not live in an object-typed field. A
  client parses the string and copes with one that does not parse.
- A run's `summary` is one line per step, joined; a step's is one line read out of that peer's own
  answer.
- `POST` answers 202 and does not wait. A gc run is minutes of somebody else's pruning; the client
  polls `GET /runs/{id}` every two seconds while it is `RUNNING`.

The document is at `/orchestrator/q/openapi`, the browsable UI at `/orchestrator/q/swagger-ui`, and
readiness at `/orchestrator/q/health/ready`. The client is served at `/orchestrator/`.

## Configuration

Every key below is defaulted in the domain jar (`orchestrator/src/main/resources/META-INF/microprofile-config.properties`)
and overridable by environment without a rebuild.

| key | default | what it decides |
|---|---|---|
| `qits.orchestrator.targets.artifacts-url` | `http://qits-artifacts:8080` | where qits-artifacts is |
| `qits.orchestrator.targets.containers-url` | `http://qits-containers:8080` | where qits-containers is |
| `qits.orchestrator.targets.ci-url` | `http://qits-ci:8080` | where qits-ci is |
| `qits.orchestrator.targets.deployments-url` | `http://qits-platform-deployments:8080` | where the deployer is |
| `qits.orchestrator.gc.enabled` | `true` | whether the CLOCK may start a run |
| `qits.orchestrator.gc.cron` | `0 40 3 * * ?` | when it does |
| `qits.orchestrator.gc.dry-run` | `false` | whether the SCHEDULED run may delete |
| `qits.orchestrator.gc.image-keep-prefixes` | `qits/build-images/,qits/graalvmce-musl-builder` | tag prefixes no rule may condemn |
| `qits.orchestrator.gc.image-min-age` | `PT6H` | the build-then-push grace for an image |
| `qits.orchestrator.gc.volume-min-age` | `PT24H` | the stop-then-start grace for a volume |
| `qits.orchestrator.gc.build-cache-keep-bytes` | `21474836480` | what buildkit may keep after a prune |
| `qits.orchestrator.gc.call-timeout` | `PT120S` | how long one peer call may take |
| `qits.auth.machine.audience` | `qits-platform-orchestrator` | this service's own id at qits-platform-idp |

**Three tier services by configured url.** qits-artifacts, qits-containers and qits-ci are per
environment (`dev-qits-artifacts`) while this service is platform tier, so a live platform injects
the qualified names. Known debt, the same one qits-configuration carries.

**Outbound credentials** are four named oidc clients — `artifacts`, `containers`, `ci`,
`deployments` — all `client-id=qits-platform-orchestrator`, all shipped `client-enabled=false`. A
token is cut for one service, which is why there are four; only the audience differs, and it is the
one value not defaulted, because it is environment-qualified. A deployment turns one on with

```
QUARKUS_OIDC_CLIENT_ARTIFACTS_CLIENT_ENABLED=true
QUARKUS_OIDC_CLIENT_ARTIFACTS_CREDENTIALS_SECRET=<this service's idp client secret>
QUARKUS_OIDC_CLIENT_ARTIFACTS_GRANT_OPTIONS_CLIENT_AUDIENCE=dev-qits-artifacts
```

Off, calls go out with the forward-auth pair alone (`X-Qits-User: qits-platform-orchestrator`,
`X-Qits-Roles: qits:system`), which every call carries regardless.

**The store** is its own PostgreSQL database, `qits_platform_orchestrator`, declared by
`resources: postgresql:db` in `.config/qits/deployments.yml` and handed over as
`QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`. Two tables: `op_run` and `op_step`. It is the
only account of a deletion that exists on this platform — not a cache.

## Building and testing

```
./mvnw clean verify -Dquarkus.http.test-port=0
```

Green on a clone with **no docker and no credentials** — the suite spawns its own PostgreSQL from a
Maven artifact (zonky) and the peers are faked. It needs two things: a **node on PATH** and an
**initialised webui submodule**, because `verify` runs `package` and `package` is where Quinoa
builds the client. `./mvnw test` needs neither — Quinoa is off in test mode.

`-Dquarkus.http.test-port=0` is not optional on the deployment host: Quarkus' default test port
8081 is the platform's own npm registry there.

Integration tests are skipped by default. `-DskipITs=false` runs `PackagedSurfaceIT` against the
fast-jar; `-Dnative` builds the GraalVM binary (`.sdkmanrc` names `25.0.2-graalce`) and runs it
against that.

The image is `docker/Dockerfile`, built from the repo root with the client bundle already in the
context — see `AGENTS.md`.
