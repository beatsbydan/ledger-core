# LedgerCore

A double-entry ledger and settlement engine that proves a transfer can move money between two accounts under concurrent load without ever double-spending, overdrawing, or losing an event.

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Apache Kafka · Liquibase · k6

**[Read the full product document →](docs/PRODUCT.md)**

- [Architecture diagrams](docs/diagrams) — system context, every key flow, database schema, as standalone Mermaid files
- [Load test results](docs/test-results) — 219.6 transfers/sec, p99 1.32s, zero balance drift across 28,733 transfers

## Quick start

```powershell
createdb -U postgres ledgercore
docker compose up -d kafka

$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "<your-password>"
./mvnw.cmd spring-boot:run
```

```powershell
./mvnw.cmd test   # full suite: 29 tests, real Testcontainers Postgres + Kafka
```

See [docs/PRODUCT.md](docs/PRODUCT.md) for the complete architecture, design decisions, API reference, and test strategy.
