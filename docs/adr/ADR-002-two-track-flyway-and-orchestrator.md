# ADR-002: Two-Track Flyway Migrations & Bounded-Concurrency Tenant Orchestrator

- **Status:** Accepted
- **Date:** 2026-11-14

## Context
Running Flyway sequentially across thousands of tenant databases during Spring Boot startup would cause multi-minute boot timeouts and failed readiness probes.

## Decision
1. Disable Spring Boot `FlywayAutoConfiguration`.
2. Split migrations into two explicit Flyway tracks:
   - `classpath:db/migration/control`: executed automatically when `controlPlaneDataSource` initializes.
   - `classpath:db/migration/tenant`: executed on tenant provisioning, via `TenantMigrationOrchestrator` (bounded `ExecutorService`, `migrationConcurrency = 4`, halts if failures exceed `migrationFailureThreshold`), and lazily verified on first pool checkout when `tenants.schema_version != expectedTenantSchemaVersion`.
3. All migrations must be forward-only and re-runnable.

## Trade-offs
- **Pros:** Fast zero-downtime app restarts regardless of tenant count; controlled blast radius during rollouts.
- **Cons:** Tenant schemas can transiently sit one version behind until touched or migrated by the batch orchestrator; application code must tolerate N-1 schema compatibility during rolling deploys.
