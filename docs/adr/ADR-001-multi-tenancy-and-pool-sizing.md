# ADR-001: Database-per-Tenant Multi-Tenancy, Pool Sizing & Cluster Sharding Path

- **Status:** Accepted
- **Date:** 2026-11-14

## Context
Proofly stores sensitive staff identity, attendance, and encrypted location PII per employer. Regulatory expectations (NDPR/GDPR) and enterprise buyer requirements strongly favor physical schema/database isolation so tenant offboarding, backup/restore, and blast-radius containment remain clean.

## Decision
1. **Control-Plane DB + Database-per-Tenant:**
   - Control-plane DB stores `tenants`, `global_user_directory` (login routing), `plans`, `subscriptions`, double-entry billing ledger, `payments`, `reconciliation_issues`, and `platform_audit_log`.
   - Each tenant organization gets its own PostgreSQL 16 + PostGIS database (`proofly_t_<uuid>`).
2. **Strict JWT-Driven Routing:**
   - `TenantContext` is populated *only* from the verified JWT `tid` claim via `TenantContextFilter` (or explicit `TenantContext.runWithTenant(...)` in internal workflows). Client `X-Tenant-ID` headers are ignored.
   - `TenantRoutingDataSource` sets `lenientFallback = false` and throws `PROOFLY_TENANT_CONTEXT_MISSING` if accessed without a bound tenant.
3. **Bounded LRU HikariCP Pools + PgBouncer Math:**
   - Each API instance maintains a Caffeine LRU cache (`expireAfterAccess = 20m`, `maximumSize = 25`) of small HikariCP pools (`perTenantMaxPoolSize = 3`, `minimumIdle = 0`).
   - `globalConnectionCap = 90` ensures at most `floor(90 / 3) = 30` cached tenant pools per API replica.
   - With `N = 4` API replicas + `12` control-plane connections/replica, peak direct connections = `4 * (90 + 12) = 408`. Behind **PgBouncer in `transaction` pool mode** (`prepareThreshold=0`, `preparedStatementCacheQueries=0`), physical server connections stay bounded by PgBouncer's `DEFAULT_POOL_SIZE`.
4. **Backup/Restore & Tenant Offboarding:**
   - Per-tenant backup: `pg_dump -Fc proofly_t_<id>`; restore into an isolated DB and update `tenants.db_host/db_name` atomically without touching sibling tenants.
   - Offboarding/erasure: mark tenant `DELETING`, revoke all active refresh families, evict pools across replicas, export final compliance archive if requested, then execute `DROP DATABASE` + redact `global_user_directory`.
5. **Upgrade Path to Multi-Cluster Sharding:**
   - `tenants` already stores `db_host`, `db_port`, `db_name`, and `db_secret_ref`. Adding new PostgreSQL clusters only requires routing new signups to a cluster selector in `TenantProvisioningService` with zero schema changes.

## Trade-offs
- **Pros:** Strong isolation, trivial per-tenant GDPR deletion, isolated noisy-neighbor blast radius, zero `WHERE tenant_id = ?` leakage risk.
- **Cons:** Higher idle connection overhead without PgBouncer, fan-out migration complexity (mitigated in ADR-002), cross-tenant reporting must aggregate asynchronously via control plane.
