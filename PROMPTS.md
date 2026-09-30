# Rebuild Prompts — Distributed Fault-Tolerant Job Scheduler

Feed these prompts to Claude Code, in order, in a fresh empty directory. Each one
maps to a real phase this project actually went through (see `git log`), including
the two real bugs that got found and fixed mid-build. Run the "Verify" step after
each prompt before moving to the next — several later phases depend on behavior
established earlier.

Prerequisites: Java 21, Maven, Docker Desktop, a local MySQL 8 and Redis (or just
use Docker for those — Phase 5 sets that up).

---

## Phase 0 — Project scaffold

**Prompt:**
> Create a new Spring Boot 3.x (or latest stable) Maven project, groupId
> `com.nitinkhandelwal`, artifactId `job-scheduler`, Java 21. Add these
> dependencies: Spring Web (MVC), Spring Data JPA, Spring Boot Validation,
> `mysql-connector-j` (runtime scope), Lombok (optional). Set up
> `src/main/resources/application.properties` with a MySQL datasource pointing
> at `jdbc:mysql://localhost:3306/job_scheduler`, user `scheduler_user`,
> password `scheduler_pass`, `spring.jpa.hibernate.ddl-auto=update`, and
> `spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect`.
> Base package: `com.nitinkhandelwal.job_scheduler`.

**Verify:** `mvn spring-boot:run` starts cleanly against a running local MySQL
with an empty `job_scheduler` database.

---

## Phase 1 — Job entity, idempotent submission API

**Prompt:**
> Add a `Job` JPA entity (table `jobs`) with: `id` (Long, identity), 
> `idempotencyKey` (String, unique, not null), `taskType` (String, not null), 
> `payload` (String, column type JSON, not null), `status` (String, not null, 
> default `"PENDING"`), `priority` (Integer, default 0), `attempts` (Integer, 
> default 0), `maxAttempts` (Integer, default 3), `scheduledAt` (LocalDateTime, 
> not null), `lockedBy` (String, nullable), `lastHeartbeat` (LocalDateTime, 
> nullable), `createdAt`/`updatedAt` (LocalDateTime), `errorMessage` (String, 
> TEXT column). Use Lombok `@Data`.
>
> Add a `JobRepository extends JpaRepository<Job, Long>` with a
> `findByIdempotencyKey(String)` method.
>
> Add a `CreateJobRequest` DTO with bean validation: `idempotencyKey` and
> `taskType` `@NotBlank`, `payload` and `scheduledAt` `@NotNull`, `priority`
> defaulting to 0, `maxAttempts` defaulting to 3.
>
> Add a `JobController` with `POST /api/jobs`: if a job with the given
> `idempotencyKey` already exists, return it with 200 OK instead of creating a
> duplicate; otherwise create a new job with status `PENDING` and return 201.
> Add `GET /api/jobs/{id}`.

**Verify:** POST the same `idempotencyKey` twice — second call returns the
same row, no duplicate created. `SELECT * FROM jobs` shows one row.

---

## Phase 2 & 3 — Worker polling loop with atomic job claiming

**Prompt:**
> Add a native query to `JobRepository` — `findNextClaimableJobId(LocalDateTime
> now)` — that runs:
> ```sql
> SELECT id FROM jobs
> WHERE status = 'PENDING' AND scheduled_at <= :now
> ORDER BY priority DESC, scheduled_at ASC
> LIMIT 1
> FOR UPDATE SKIP LOCKED
> ```
> This is the concurrency-safety mechanism: when multiple worker instances
> query at the same instant, MySQL guarantees each gets a different row (or
> none) with zero blocking and zero duplicate claims.
>
> Add a `markJobAsRunning(Long jobId, String workerId)` `@Modifying` query that
> sets `status='RUNNING'`, `lockedBy=:workerId`, `lastHeartbeat=now`,
> `attempts=attempts+1`.
>
> Add a `JobWorker` `@Component` with a `@Scheduled(fixedDelay = 2000)` method
> that: calls `findNextClaimableJobId` then `markJobAsRunning` in one
> transaction, logs what it claimed, and (for now) just marks it `COMPLETED`
> immediately as a placeholder for real task execution. Give each worker
> instance a unique in-memory ID (e.g. `"worker-" + System.currentTimeMillis()`).
>
> Write a JUnit test (`@SpringBootTest`) that saves one `PENDING` job, fires 10
> concurrent threads all calling the claim logic at once, and asserts exactly
> one of them succeeds in claiming it.

**Verify:** `mvn test -Dtest=JobWorkerConcurrencyTest` passes reliably across
repeated runs (not flaky).

---

## Phase 4a — Heartbeats, and a self-invocation transaction bug

**Prompt:**
> Add Spring Data Redis. Add a `HeartbeatService` that writes a Redis key
> `worker:heartbeat:<workerId>` with a 6-second TTL (`sendHeartbeat`), plus an
> `isWorkerAlive` check.
>
> Change `JobWorker`'s claimed-job handling to actually "execute" the task: for
> now, simulate work with a loop of 3 iterations, each sending a heartbeat
> (both the Redis key and a `last_heartbeat` timestamp update on the `Job` row)
> and sleeping 1 second, so a job visibly takes ~3 seconds and refreshes
> liveness signals while running.
>
> **While wiring this up you'll hit a real Spring pitfall:** if
> `@Transactional` claim/update methods live directly on `JobWorker` and
> `JobWorker` calls them via `this.someMethod()`, Spring's proxy-based AOP
> never intercepts the call, so `@Transactional` silently does nothing (no
> transaction, no rollback safety). Fix it the way this project did: extract
> all the claim/complete/fail database operations out of `JobWorker` into a
> separate `@Service` class `JobClaimService`, and have `JobWorker` call
> methods on the *injected* `JobClaimService` bean instead of on itself. This
> forces every transactional method call to go through the real Spring proxy.

**Verify:** Run two instances on different ports locally; watch logs — each
claimed job shows 3 heartbeat log lines about a second apart, and Redis
(`redis-cli keys 'worker:heartbeat:*'`) shows a live key with TTL while a job
is running.

---

## Phase 4 — The Reaper: crash recovery

**Prompt:**
> Add a `JobReaper` `@Component` with a `@Scheduled(fixedDelay = 5000)`,
> `@Transactional` method `reclaimStaleJobs()`. Add a `reclaimStaleJobs`
> `@Modifying` query to `JobRepository`:
> ```sql
> UPDATE Job j
> SET j.status = CASE WHEN j.attempts >= j.maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
>     j.lockedBy = NULL,
>     j.updatedAt = CURRENT_TIMESTAMP
> WHERE j.status = 'RUNNING'
> AND j.lastHeartbeat < :staleThreshold
> ```
> A job stuck in `RUNNING` whose heartbeat hasn't updated recently is assumed
> to belong to a crashed worker: reset it to `PENDING` for a healthy worker to
> pick up, or `DEAD` if it's already exhausted its retries. Gate both
> `JobWorker`'s polling and `JobReaper`'s reclaiming behind a
> `scheduler.polling.enabled` property (default true) so tests can disable the
> background loops.

**Verify — do this for real, not just in a unit test:** start two worker
instances against the same database, submit a job, and once a worker claims
it, `kill -9` (or `docker kill`) that worker process mid-execution. Within a
few seconds the reaper (running in the *other* instance) should reset the job
to `PENDING`, and the surviving worker should pick it up and complete it.
Confirm this in the logs.

---

## Phase 4b — Dockerize into a real multi-instance cluster

**Prompt:**
> Add a multi-stage `Dockerfile` (Maven build stage on
> `maven:3.9-eclipse-temurin-21`, runtime stage on `eclipse-temurin:21-jre`,
> `mvn clean package -DskipTests` then run the jar). Add a
> `docker-compose.yml` with: a `mysql:8.0` service (with a healthcheck), a
> `redis:7` service, and **two** instances of this app (`worker1` on host port
> 8080, `worker2` on host port 8081), both pointed at the same MySQL/Redis via
> environment variables (`SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`,
> etc.), both depending on MySQL being healthy first.

**Verify:** `docker compose up --build` brings up a real 2-container cluster
sharing one database — this is what exposes container-specific timing bugs
that `localhost` testing won't.

---

## Phase 5 — Retry with exponential backoff + jitter, Dead Letter Queue

**Prompt:**
> Add a `markJobFailed(Long jobId, LocalDateTime nextAttemptAt, String
> errorMessage)` `@Modifying` query that sets
> `status = CASE WHEN attempts >= maxAttempts THEN 'DEAD' ELSE 'PENDING' END`,
> updates `scheduledAt` to `nextAttemptAt`, clears `lockedBy`, and stores
> `errorMessage`.
>
> In `JobWorker`, wrap task execution in try/catch. On exception: compute a
> backoff delay as `2^(attempts-1) * 5` seconds plus 0–3 seconds of random
> jitter (so retries don't all land at once — avoid a thundering herd), call
> `markJobFailed` with `now + backoff` as the next attempt time, and log
> whether the job is retrying or has now exhausted `maxAttempts` and moved to
> `DEAD`. Add a task type value like `"SIMULATE_FAILURE"` that always throws,
> purely for testing this path end-to-end.

**Verify:** Submit a job with `taskType: "SIMULATE_FAILURE"` and
`maxAttempts: 3`. Watch it retry with visibly increasing delays (~5s, ~10s,
~20s) in the logs, then land in `status = 'DEAD'` with `error_message`
populated after the third failure.

---

## Phase 5b — Fix a real double-completion race condition

This is a bug that was only found by testing in the real Docker cluster from
Phase 4b, not in local/unit testing. Worth understanding, not just copying:

> **The bug:** a worker that's slow to send its *first* heartbeat — because JVM
> startup inside a container takes a few seconds — can have its job falsely
> reclaimed by the Reaper (running on another instance) before it ever gets a
> chance to report in. The original worker, still alive, has no idea ownership
> shifted, finishes the task, and writes `COMPLETED` anyway — as does whatever
> second worker picked up the reclaimed job. Same job completes twice.
>
> **Root cause:** completion/failure updates trusted the worker's local
> in-memory belief that it still owned the job, with no check against current
> DB state.

**Prompt:**
> Change `markJobCompleted` and `markJobFailed` to be ownership-checked: add
> `AND lockedBy = :workerId AND status = 'RUNNING'` to their `WHERE` clauses
> (pass `workerId` in), and make both return the number of rows updated as an
> `int`/`boolean`. In `JobWorker`, check that return value — if 0 rows were
> updated, log that ownership was lost and skip declaring success/failure
> instead of blindly trusting local state. Also raise the reaper's stale
> heartbeat threshold (`JobReaper`'s `STALE_THRESHOLD_SECONDS`) from whatever
> it currently is to something generous enough to tolerate container JVM
> startup latency — this project settled on **15 seconds**.

**Verify:** Re-run the Phase 4 kill test in the Docker cluster a few times —
no job should ever end up processed by two workers. `SELECT id, status,
locked_by FROM jobs` should never show a job that both instances logged as
completed.

---

## Phase 6 — Metrics, and a request-parsing bug

**Prompt:**
> Add Spring Boot Actuator and Micrometer. Add a `JobMetrics` `@Component`
> with four `Counter`s registered on the `MeterRegistry`:
> `jobs.completed.total`, `jobs.failed.total`, `jobs.dead.total`,
> `jobs.reclaimed.total`, plus a gauge `workers.active` that counts live
> `worker:heartbeat:*` keys in Redis. Wire increments into `JobWorker` (on
> success, on failure, on moving to `DEAD`) and `JobReaper` (on each batch of
> reclaimed jobs). Expose `health,metrics,prometheus` via
> `management.endpoints.web.exposure.include`.
>
> **While doing this, check `JobController`:** the original code building a
> `Job` from `CreateJobRequest` forgot to copy `maxAttempts` from the request
> onto the entity, so every job silently used the entity's default of 3
> regardless of what the client asked for. Fix that — make sure every field on
> `CreateJobRequest` is actually copied onto the `Job` before saving.

**Verify:** `GET /actuator/metrics/jobs.completed.total` reflects real counts
after processing jobs. Submit a job with `"maxAttempts": 1` and confirm (via
DB or logs) it actually goes `DEAD` after just one failure, not three.

---

## Phase 7 — README and architecture documentation

**Prompt:**
> Write a `README.md` documenting: why this project exists (the coordination
> problem with naive `@Scheduled` across multiple instances), the tech stack
> table, an architecture walkthrough of the full job lifecycle
> (`PENDING → RUNNING → COMPLETED`, with branches for retry-with-backoff and
> reaper-reclaim), a Mermaid flowchart of the architecture, a "Core mechanisms"
> section covering the five key techniques (atomic claiming, heartbeats, the
> reaper, backoff+jitter, ownership-safe completion), a section walking through
> the real double-completion race condition that was found and fixed (root
> cause + fix, framed as *fencing*), how to run it locally via
> `docker compose up --build`, example `curl`/HTTP calls for submitting a job
> and reading metrics, how to run the concurrency test, and an honest "what I'd
> add with more time" section (Testcontainers, a load-testing harness,
> Prometheus/Grafana, externalized secrets).

**Verify:** Read it as a stranger would — it should make the ownership-race
bug fix understandable without reading the diff.

---

## Phase 8 — Regression tests for the reaper and DLQ paths

At this point the project had solid manual/Docker verification for the reaper
and dead-letter paths, but no automated regression coverage for either — a gap
worth closing, especially since one of them (the ownership race) was a real
production-shaped bug.

**Prompt:**
> Add a `JobReaperTest`: cover a stale `RUNNING` job with attempts remaining
> getting reclaimed to `PENDING`; a stale `RUNNING` job already at
> `maxAttempts` moving straight to `DEAD` via the reaper (not just via
> `markJobFailed`); a `RUNNING` job with a *fresh* heartbeat being left alone;
> a `PENDING` job being untouched; and `scheduler.polling.enabled=false`
> making the reaper component a true no-op. Note: `reclaimStaleJobs` is a bulk
> `@Modifying` JPQL query — it needs an active transaction and bypasses
> Hibernate's persistence context, so wrap the test in `@Transactional` and
> call `entityManager.clear()` after each bulk update before re-reading the
> row via `findById`, or you'll read stale cached entity state instead of what
> actually landed in the database.
>
> Add a `JobDlqTest` covering the ownership-checked `markJobFailed`: attempts
> below `maxAttempts` retries back to `PENDING` with the backoff `scheduledAt`
> and error message set; attempts at `maxAttempts` lands in `DEAD`; and — as a
> direct regression test for the Phase 5b race condition — a failure reported
> by a `workerId` that does **not** match the job's current `lockedBy` is
> rejected outright (0 rows updated, job state untouched).

**Verify:** `mvn test` — full suite green, including the new classes, against
a real local MySQL + Redis.

---

## Phase 9 — Turn off verbose Hibernate SQL logging

**Prompt:**
> Set `spring.jpa.show-sql=false` in `application.properties` — every executed
> SQL statement (including the `FOR UPDATE SKIP LOCKED` claim query) was being
> printed to stdout every 2 seconds by every idle worker, which was pure log
> noise once the query itself was understood and no longer being debugged.

**Verify:** Restart the app — no more `Hibernate: SELECT ...` lines flooding
the console during normal polling.

---

## End state checklist

After all phases, you should have:
- `POST /api/jobs` (idempotent) and `GET /api/jobs/{id}`
- A worker polling loop claiming jobs via `SELECT ... FOR UPDATE SKIP LOCKED`
- Redis + DB heartbeats, sent from a service reached through a real Spring
  proxy (not a self-invocation)
- A reaper reclaiming stale `RUNNING` jobs every 5s (15s staleness threshold)
- Retry with exponential backoff + jitter, and a Dead Letter Queue (`DEAD`)
- Ownership-checked completion/failure updates (the fencing fix)
- Micrometer counters/gauge exposed via Actuator
- A 2-container Docker Compose cluster sharing one MySQL + Redis
- `JobWorkerConcurrencyTest`, `JobReaperTest`, `JobDlqTest` — all green
- A README explaining all of the above, including the bug that was found and
  fixed, with a Mermaid architecture diagram
