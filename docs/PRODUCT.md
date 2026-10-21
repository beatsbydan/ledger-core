# LedgerCore

A double-entry ledger and settlement engine that proves a transfer can move money between two accounts under concurrent load without ever double-spending, overdrawing, or losing an event. Built as the deep version of a Settlement Agent system — the question it answers for a reviewer is simple: *how do you stop double-spends and duplicate transfers?*

**Status:** Phases 1–3 complete, tested, and manually verified end-to-end. 29 automated tests passing. Load tested with k6 (see [`test-results/`](test-results)).

---

## Contents

1. [What this proves](#what-this-proves)
2. [Architecture at a glance](#architecture-at-a-glance)
3. [Phase 1 — Core Ledger](#phase-1--core-ledger)
4. [Phase 2 — Transactional Outbox & Kafka](#phase-2--transactional-outbox--kafka)
5. [Phase 3 — Nightly Reconciliation](#phase-3--nightly-reconciliation)
6. [Database schema](#database-schema)
7. [REST API](#rest-api)
8. [Testing strategy](#testing-strategy)
9. [Load testing](#load-testing)
10. [Running it locally](#running-it-locally)
11. [Tech stack](#tech-stack)
12. [Known limitations & deliberate non-goals](#known-limitations--deliberate-non-goals)

---

## What this proves

Three correctness properties, each backed by a concurrency test that actually exercises it under contention rather than just asserting the happy path:

- **No lost updates.** Two transfers racing for the same account never silently overwrite each other's balance change.
- **No deadlocks.** Two transfers racing in opposite directions between the same two accounts never deadlock.
- **No double-spends from retries.** A client retrying a transfer request — because a response was lost, a timeout fired, anything — never moves money twice.

All three are proven, not assumed: `ConcurrentTransferTest` fires 80 concurrent transfers alternating direction between two accounts and asserts the total balance never drifts and neither account ever goes negative; `IdempotencyConcurrencyTest` fires 20 concurrent duplicate requests at the same `Idempotency-Key` and asserts exactly one transfer executes; `ConcurrentOutboxPublishTest` drains 120 outbox rows with 6 concurrent "relay instances" and confirms every single one reaches Kafka exactly once.

## Architecture at a glance

Full diagrams (Mermaid source, open in [Mermaid Live Editor](https://mermaid.live) or any Mermaid-aware viewer) are in [`diagrams/`](diagrams). The headline one:

![system context](diagrams/01-system-context.mmd)

```
Client --[HTTP + Idempotency-Key]--> Transfer API --[locked read/write]--> PostgreSQL
                                           |
                                    (same transaction)
                                           v
                                    Outbox --> Kafka --> downstream consumers
                                           
PostgreSQL --[nightly]--> Reconciliation Job --> simulated bank statement
```

The Transfer API and PostgreSQL are the entire request path. Everything downstream of a commit — Kafka publishing, reconciliation — happens asynchronously and can never affect whether a transfer itself succeeds or fails.

## Phase 1 — Core Ledger

**What it is:** accounts, a double-entry journal, an idempotent transfer API, and pessimistic-lock concurrency control.

**Key decisions:**

| Decision | Why |
|---|---|
| Pessimistic row locks (`SELECT ... FOR UPDATE`), not optimistic versioning | A failed optimistic write on a money transfer leaves a half-applied transaction to unwind; a lock-held transaction is all-or-nothing by construction. Matches how real ledger systems and core-banking engines serialize per-account mutations. |
| Locks acquired in **ascending account-id order**, regardless of debit/credit role | Two transfers racing in opposite directions between the same two accounts always request the lower id first — the classic two-resource deadlock becomes structurally impossible, not just unlikely. See [Fig. 5](diagrams/05-concurrency-deadlock-avoidance.mmd). |
| Idempotent transfers use **three independent transactions** (claim → locked transfer → finalize) | The idempotency claim must commit and become visible to a racing duplicate *before* the transfer it guards has even finished — one shared transaction would defeat the point. See [Fig. 4](diagrams/04-idempotency-flow.mmd). |
| Money as `BIGINT` minor units, never floating point | Eliminates an entire class of rounding bugs by construction. |
| Zero-sum and immutability enforced by **Postgres deferred constraint triggers**, not just application code | A plain `CHECK` constraint can't see sibling rows, so it structurally cannot express "this entry's debits and credits sum to zero." A deferred constraint trigger can — it fires per-row but evaluates at commit, once every line for the entry exists. The trigger is the unbypassable backstop; app-level validation is just the fast-fail layer in front of it. |
| Corrections via **reversal**, never edit-in-place | `journal_entries`/`journal_lines` reject every `UPDATE`/`DELETE` at the trigger level. A correction is a brand-new entry referencing the one it corrects — the full history, including every mistake and its correction, is always replayable. |

**Endpoints:** `POST /api/v1/accounts`, `GET /api/v1/accounts/{accountNumber}`, `POST /api/v1/transfers`, `GET /api/v1/transfers/{transferId}`.

## Phase 2 — Transactional Outbox & Kafka

**What it is:** every journal entry (transfer or reversal) emits exactly one Kafka event, with a guarantee that the event and the ledger write can never disagree about whether something happened.

**Key decisions:**

| Decision | Why |
|---|---|
| Outbox row written in the **same transaction** as the journal entry | The entire point of the pattern: commits or rolls back together with the journal write. No second transaction that could fail independently. See [Fig. 7](diagrams/07-outbox-write.mmd). |
| A separate, stateless relay polls and publishes — the Transfer API never talks to Kafka directly | Decouples "did the transfer succeed" from "did Kafka accept the event," which can fail/retry/be slow for entirely unrelated reasons. |
| Relay uses `SELECT ... FOR UPDATE SKIP LOCKED` | Lets more than one relay instance poll concurrently without double-claiming the same row — proven, not assumed, by `ConcurrentOutboxPublishTest`. |
| Delivery is **at-least-once**, deliberately | A crash between Kafka's ack and the status update can republish an event. Rather than engineering that race away, consumers are expected to dedupe by `entry_uuid` — the same key the idempotency flow already uses for the exact same reason. |
| One event per journal entry, keyed by `entry_uuid`, not per-account | A transfer touches two accounts; no single Kafka key preserves order for both. Per-account ordering/windowing is left to whatever consumes the stream, not baked into publishing. |

## Phase 3 — Nightly Reconciliation

**What it is:** a nightly job that compares the ledger against a simulated external bank statement and flags every disagreement, using constant memory regardless of dataset size.

**Key decisions:**

| Decision | Why |
|---|---|
| **Sort-merge join**, not a hash join | Loading either side into an in-memory hash map doesn't scale — the same lesson that motivates streaming any large batch job. Two cursors advance over pre-sorted streams instead; memory use is O(1). See [Fig. 11](diagrams/11-reconciliation-merge-join.mmd). |
| Match/sort key is the **composite** `(entryUuid, accountNumber)`, not `entryUuid` alone | One transfer produces two lines (debit + credit, two different accounts) — `entryUuid` alone doesn't uniquely identify a row to compare. |
| Ledger side streamed via a **fetch-size-hinted JPQL query** (real server-side cursor) | The concrete mechanism behind "constant memory" — omitting the hint silently defeats the whole point, since Hibernate would otherwise materialize the full result set before handing back a `Stream`. |
| Mismatches emitted via `Consumer<Mismatch>`, not accumulated in a `List` | There's no known upper bound on mismatch count going into a run — a badly-broken reconciliation shouldn't itself become a memory problem. |
| Three mismatch types: `LEDGER_ONLY`, `BANK_ONLY`, `AMOUNT_MISMATCH` | Each points at a different real failure mode — a transfer that never settled externally, external activity with no internal record, or a silent amount discrepancy where both sides agree *something* happened. |
| The job **detects, never corrects** | A mismatch can mean a timing lag, a bug, or fraud — deciding which requires human judgment the job doesn't have. Any correction goes through the existing reversal path, as a separate, audited, human-initiated action. |
| Guarded against overlapping runs; one malformed line is skipped, not fatal | A stuck previous run must not let a second one start concurrently; a single corrupted line in a multi-million-row file must not void the whole night's reconciliation. |

No bank-statement generator was built — it's explicitly not the interesting engineering problem here. The simulated statement is produced with a one-line `psql COPY (...) TO STDOUT WITH CSV` export from the real ledger, hand-edited to seed mismatches for demos. See `load-test/` for the equivalent idea applied to load testing (seed data generated by the test itself, not a separate tool).

## Database Schema

Seven tables across three phases — full ER diagrams in [`diagrams/`](diagrams):

- **Phase 1:** `accounts`, `journal_entries`, `journal_lines`, `idempotency_keys` — [Fig. 6](diagrams/06-core-ledger-erd.mmd)
- **Phase 2:** `outbox_events` — [Fig. 9](diagrams/09-outbox-erd.mmd)
- **Phase 3:** `reconciliation_runs`, `reconciliation_mismatches` — [Fig. 12](diagrams/12-reconciliation-erd.mmd)

All schema changes are Liquibase changesets (`src/main/resources/db/changelog/`), applied automatically on startup — there is no manual migration step.

## REST API

| Method & Path | Success | Failure modes |
|---|---|---|
| `POST /api/v1/accounts` | `201` | `409 ACCOUNT_EXISTS` |
| `GET /api/v1/accounts/{accountNumber}` | `200` | `404 ACCOUNT_NOT_FOUND` |
| `POST /api/v1/transfers` (requires `Idempotency-Key` header) | `201` (new transfer, or exact replay of a prior identical request) | `400 INVALID_TRANSFER` / `VALIDATION_ERROR`, `404 ACCOUNT_NOT_FOUND`, `409 ACCOUNT_NOT_ACTIVE` / `IDEMPOTENCY_KEY_CONFLICT` / `REQUEST_IN_PROGRESS`, `422 INSUFFICIENT_FUNDS` |
| `GET /api/v1/transfers/{transferId}` | `200` | `404 TRANSFER_NOT_FOUND` |

Every error response is a consistent `{timestamp, status, error, message, path}` shape (`GlobalExceptionHandler`), with `error` a stable machine-readable code — not just an HTTP status — so a client can branch on it without string-matching a message.

## Testing Strategy

**29 automated tests**, all against real infrastructure (Testcontainers Postgres + Kafka — never H2 or an embedded broker, since the invariants under test are Postgres/Kafka-specific behavior a fake can't faithfully emulate):

| Class | What it proves |
|---|---|
| `JournalInvariantTest` | Zero-sum and immutability triggers actually reject what they should; reversal produces correct structure |
| `TransferServiceTest` | Happy path, insufficient funds, same-account rejection, unknown account |
| `ConcurrentTransferTest` | No lost updates, no deadlock, no balance drift — 80 concurrent transfers, real contention |
| `IdempotencyConcurrencyTest` | Concurrent duplicate requests resolve to exactly one transfer; conflicting bodies are rejected; retry-after-failure works |
| `OutboxWriteTest` | Every journal entry emits exactly one correctly-shaped outbox event |
| `OutboxPublisherTest` | End-to-end publish to a real Kafka broker |
| `ConcurrentOutboxPublishTest` | `SKIP LOCKED` actually prevents double-publishing — 120 rows, 6 concurrent relay instances, zero duplicates |
| `ReconciliationMergeJoinTest` | Every branch of the merge-join algorithm, pure and in-memory — no DB or file I/O |
| `ReconciliationJobTest` | End-to-end: real transfers, a crafted statement file, all three mismatch types caught correctly |
| `ReconciliationGuardTest`, `ReconciliationEdgeCaseTest` | Overlapping-run guard, malformed-line skip, missing-file handling |

Several of these tests caught **real bugs** during development, not just hypothetical ones:

- A lost-update bug where an unlocked account lookup before the locked one let Hibernate's session cache return a stale entity, silently defeating the row lock.
- An `UnexpectedRollbackException` from catching a unique-constraint violation inside the same `@Transactional` method that caused it — the JPA spec marks that transaction rollback-only regardless of whether the exception is caught.
- Postgres trigger exceptions not translating to Spring's `DataIntegrityViolationException` without an explicit SQLSTATE.
- A Testcontainers footgun where `@Testcontainers`/`@Container` ties a container's lifecycle to each test class and stops it after that class finishes — even on a `static` field — killing it out from under every later test class.

## Load Testing

k6 load test and a balance-conservation check, in [`load-test/`](../load-test). Full results and methodology are in [`test-results/`](test-results); headline numbers from the latest run:

| Metric | Result |
|---|---|
| Throughput | 219.6 transfers/sec at 50 concurrent VUs |
| p99 latency | 1.32s |
| Error rate | 0.00% (0 of 28,833 requests) |
| Balance conservation | PASS — zero drift across 28,733 transfers |

```powershell
cd load-test
./run-load-test.ps1 -PgPassword 'your-postgres-password'
```

This seeds its own accounts (`LOADTEST-<run-id>-*`), runs a ramping-VU load profile against `POST /transfers`, and afterward verifies via `psql` that the total balance across every account it created is *exactly* what it started with — not approximately, exactly — since every transfer in the run only ever moves money between those accounts.

## Running It Locally

Prerequisites: Java 21, native PostgreSQL (the project assumes you already have one, rather than wrapping it in Docker), Docker (for the local Kafka broker only — see `docker-compose.yml`).

```powershell
# one-time: create the database
createdb -U postgres ledgercore

# start local Kafka (only needed once per machine session)
docker compose up -d kafka

# run the app (Liquibase migrations apply automatically on startup)
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "<your-password>"
./mvnw.cmd spring-boot:run
```

Run the full test suite (spins up its own ephemeral Testcontainers Postgres + Kafka, no setup needed):

```powershell
./mvnw.cmd test
```

## Tech Stack

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Liquibase · Apache Kafka (KRaft mode) · spring-kafka · Maven (with the Maven Wrapper, so a global Maven install isn't required) · Testcontainers · k6.

## Known Limitations & Deliberate Non-Goals

Documented explicitly, not accidental gaps:

- **No dead-letter path for outbox publish failures.** A row that never successfully publishes retries forever (unbounded `attempts` counter). Alerting on a stuck row is future work, not solved here.
- **A crash between a transfer committing and its idempotency record being finalized** can leave an orphaned `IN_PROGRESS` idempotency row. The staleness-based reclaim bounds the damage but doesn't fully close the window — closing it needs checking for an existing journal entry before reclaiming, scoped as Phase 2 hardening, not Phase 1/2 as built.
- **The reconciliation overlapping-run guard is best-effort**, not a DB-level lock — proportionate for a single-instance nightly batch job; a real multi-instance deployment would need more.
- **No CSV library for the bank statement parser** — a plain comma-split, since the file is simulated/internal, not user-uploaded. Documented simplifying assumption, same spirit as assuming the statement's `reference` column equals LedgerCore's own `entry_uuid`.
