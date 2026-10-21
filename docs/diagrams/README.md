# Architecture Diagrams

Mermaid source for every diagram in the LedgerCore architecture design. Each file is a standalone `.mmd` — open directly in [Mermaid Live Editor](https://mermaid.live), the Mermaid CLI, or any editor's Mermaid preview extension.

| # | File | Covers |
|---|---|---|
| 1 | [`01-system-context.mmd`](01-system-context.mmd) | Whole-system view: Transfer API, Postgres, and where Phase 2/3 plug in |
| 2 | [`02-account-creation-flow.mmd`](02-account-creation-flow.mmd) | `POST /accounts` — success and duplicate-account-number rejection |
| 3 | [`03-transfer-flow.mmd`](03-transfer-flow.mmd) | `POST /transfers` — the locked critical section and every rejection path |
| 4 | [`04-idempotency-flow.mmd`](04-idempotency-flow.mmd) | Replay vs. conflict vs. in-progress for a retried `Idempotency-Key` |
| 5 | [`05-concurrency-deadlock-avoidance.mmd`](05-concurrency-deadlock-avoidance.mmd) | Why ascending-id lock ordering prevents the two-account deadlock |
| 6 | [`06-core-ledger-erd.mmd`](06-core-ledger-erd.mmd) | Phase 1 schema: accounts, journal entries/lines, idempotency keys |
| 7 | [`07-outbox-write.mmd`](07-outbox-write.mmd) | The outbox insert sharing the transfer's transaction |
| 8 | [`08-outbox-relay-publish.mmd`](08-outbox-relay-publish.mmd) | The `SKIP LOCKED` relay poll loop and its failure branches |
| 9 | [`09-outbox-erd.mmd`](09-outbox-erd.mmd) | `outbox_events` schema |
| 10 | [`10-reconciliation-run-lifecycle.mmd`](10-reconciliation-run-lifecycle.mmd) | Nightly run guard, failure, and completion paths |
| 11 | [`11-reconciliation-merge-join.mmd`](11-reconciliation-merge-join.mmd) | The sort-merge-join algorithm itself |
| 12 | [`12-reconciliation-erd.mmd`](12-reconciliation-erd.mmd) | `reconciliation_runs` / `reconciliation_mismatches` schema |

These are extracted from the full annotated design — see [`../PRODUCT.md`](../PRODUCT.md) for narrative context around each one.
