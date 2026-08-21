-- The whole schema of qits-platform-orchestrator, in one migration.
--
-- One V1 and no inherited lineage: this repository starts on PostgreSQL and has never had another
-- store. From here on the ordinary rule holds — keep appending, never edit an applied migration.
--
-- TWO TABLES, AND THEY ARE A LOG. A technical process deletes things on other services, and this
-- pair is the only account of what it asked for and what it was told. `op_run` is one run; `op_step`
-- is one step of it, holding the request that was sent and the answer that came back verbatim.
-- Nothing here is derived from a peer's state later — a peer's state has moved on by then.
--
-- NO FOREIGN KEY TO ANY OTHER CONTEXT, and there will not be one: an application, an image or a
-- repository named in a request or a response is a string that belongs to another service's
-- database.

create table op_run (
    id uuid not null,

    -- Which technical process ran. `gc` is the only word v1 writes. Not a check constraint: the
    -- vocabulary is the process registry's, in code, and a historical row must keep the word it was
    -- written with even after a process is retired.
    kind varchar(64) not null,

    -- What started it: `manual` (a person pressed the button, or a machine called the route) or
    -- `scheduled` (the cron). It is the first question anyone asks of a run that deleted something
    -- surprising.
    trigger varchar(32) not null,

    -- Whether this run was allowed to delete. A dry run still sends every request — the peers'
    -- own dryRun flags make them plan instead of delete — so a dry run's figures are real figures.
    dry_run boolean not null,

    -- RUNNING, SUCCEEDED or FAILED. A run is FAILED if any step is FAILED; a skipped step does not
    -- fail a run, because a skip is the consequence of a failure already counted.
    status varchar(32) not null,

    started_at timestamp(6) with time zone not null,

    -- Null while the run is RUNNING. A row that keeps a null here forever is a process that died
    -- mid-run — the honest record of a restart, and deliberately not backfilled at boot: a
    -- successor process knows nothing about what the dead one's calls achieved.
    finished_at timestamp(6) with time zone,

    -- The run in one human line, composed from the steps when the run ends. Null while it runs.
    summary text,

    primary key (id)
);

-- The run listing is `where kind = ? order by started_at desc limit ?`, which is this index.
create index idx_op_run_kind_started_at on op_run (kind, started_at desc);

create table op_step (
    id uuid not null,

    -- The run this step belongs to. No FK: the rows are written by one component in one
    -- transaction each, and a cascade is not a behaviour this table wants — a run is never deleted.
    run_id uuid not null,

    -- Declaration order, from 0. The UI lays steps out by dependency depth, but the ORDER they ran
    -- in is this column and it is what makes a log readable.
    seq integer not null,

    -- The step's stable id in the process definition (`usage.before`, `containers.images`). It is
    -- what `depends_on` names and what the API reports as the step's `id` — the row's uuid is
    -- storage, never a wire identifier.
    step_id varchar(128) not null,

    name varchar(255) not null,

    -- Which peer the call goes to: artifacts, containers, ci, deployments. The wire name, matching
    -- qits.orchestrator.targets.<target>-url.
    target varchar(64) not null,

    -- The step ids this one waits for, comma-separated. A text column rather than a join table: the
    -- edges are a property of the DEFINITION, copied into the row so a run stays readable after the
    -- definition changes. Nothing queries by it.
    depends_on text,

    -- PENDING, RUNNING, SUCCEEDED, FAILED or SKIPPED.
    status varchar(32) not null,

    started_at timestamp(6) with time zone,
    finished_at timestamp(6) with time zone,

    -- The peer's status code, null when the call never got one (a transport failure, or a step that
    -- was skipped and never called).
    http_status integer,

    -- The request as it went out. Stored rather than reconstructed: the keep-set the orchestrator
    -- computed is IN the body, and "what did we tell it to keep" is the question a surprising
    -- deletion is investigated with.
    request_method varchar(16),
    request_url text,
    request_body text,

    -- The peer's answer, whole. Bounded at 1 MiB by the writer, which appends a truncation marker
    -- rather than silently cutting — see RunExecutor.
    response_body text,

    -- Why the step failed, or why it was skipped (`skipped: <step> failed`, `dry run`).
    error text,

    -- The step in one human line, computed from the response by the step that made the call. It is
    -- what the run listing and the cards are read from.
    summary text,

    primary key (id)
);

-- Every read of a step is "the steps of this run, in order", which is this index. It is also what
-- makes the active-run check cheap.
create index idx_op_step_run_seq on op_step (run_id, seq);
