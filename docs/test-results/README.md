# Load Test Results

**Run ID:** `20261021172528` · **Date:** 2026-10-21 · **Target:** `POST /api/v1/transfers`, native PostgreSQL + local Kafka, single app instance on a laptop (not a provisioned server)

## Summary

| Metric | Result |
|---|---|
| **Throughput** | **219.6 transfers/sec** sustained at 50 concurrent VUs |
| **p50 latency** | 91 ms |
| **p90 latency** | 376.7 ms |
| **p95 latency** | 591.2 ms |
| **p99 latency** | **1.32 s** |
| **Error rate** | **0.00%** — 0 failed out of 28,833 requests |
| **Balance conservation** | **PASS** — zero drift across 100 accounts after 28,733 transfers |

Both k6 thresholds set for this run passed: `p(99) < 2000ms` (actual 1.32s) and `http_req_failed rate < 1%` (actual 0%).

## What was tested

A ramping-VU load profile (0 → 50 VUs over 20s, hold 50 VUs for 90s, ramp down over 20s — see [`../../load-test/transfer-load-test.js`](../../load-test/transfer-load-test.js)) against 100 freshly-seeded accounts, each starting with a 10,000,000-minor-unit balance. Every iteration picks two distinct accounts at random and transfers a small amount (1–100 minor units) between them with a unique `Idempotency-Key`. 100 accounts means most concurrent transfers land on different account pairs — this measures real system throughput, not artificial lock contention on a single pair (that correctness property is already proven separately by `ConcurrentTransferTest` in the automated suite, not re-tested here).

**28,733 transfers completed, zero failures, zero threshold violations.**

## How to read the latency numbers

The gap between p95 (591ms) and p99 (1.32s) is the pessimistic-locking trade-off made explicit: this system deliberately chose correctness over raw throughput for money movement (see the architecture doc's rationale for `SELECT ... FOR UPDATE` over optimistic concurrency). The small fraction of requests at the tail are the ones that happened to queue behind another transaction holding a lock on the same account — expected and bounded behavior, not a bug. The 0% error rate confirms that even under lock contention, every request still completed correctly rather than timing out or failing.

## The balance conservation check

This is the direct answer to "does the money ever leak?" After the load test, the total balance across every account the test created was queried via `psql`:

```
Expected total (AccountCount × OpeningBalance): 1,000,000,000
Actual total across LOADTEST-20261021172528-* accounts: 1,000,000,000
Drift: 0
Result: PASS
```

Every one of the 28,733 transfers only ever moved money between these 100 accounts — never created or destroyed any of it. See [`conservation-check-20261021172528.txt`](conservation-check-20261021172528.txt) for the raw report and [`../../load-test/run-load-test.ps1`](../../load-test/run-load-test.ps1) for exactly how it's computed.

## Raw data

- [`k6-summary-20261021172528.json`](k6-summary-20261021172528.json) — full k6 metrics export (every percentile, all built-in metrics)
- [`conservation-check-20261021172528.txt`](conservation-check-20261021172528.txt) — the balance conservation report shown above

## Reproducing this

```powershell
# app must already be running (native Postgres + local Kafka, mvnw spring-boot:run)
cd load-test
./run-load-test.ps1 -PgPassword 'your-postgres-password'
```

Each run gets its own timestamped `run-id`, so repeat runs never collide with or overwrite previous results.
