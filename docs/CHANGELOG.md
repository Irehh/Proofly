# Changelog

## [P0] Foundation, Multi-Tenancy, Auth & Contracts
### Added
- Monorepo layout (`/backend`, `/apps/web`, `/apps/marketing`, `/packages/ui`, `/packages/api-client`, `/infra`, `/docs`).
- Local infrastructure stack in `/infra/docker-compose.yml` (PostgreSQL 16 + PostGIS 3.4, PgBouncer, Redis 7, Mailpit) and GitHub Actions CI workflow (`/.github/workflows/ci.yml`) with `oasdiff` breaking-change checks.
- Two-track Flyway migrations:
  - `db/migration/control/V1__init_control_plane.sql` (`plans` seeded Free–Enterprise, `tenants`, `global_user_directory`, append-only `platform_audit_log`, `shedlock`, `event_publication`).
  - `db/migration/tenant/V1__init_tenant_schema.sql` (PostGIS extension, `organization_settings`, `users`, `memberships`, `invitations`, `devices`, `refresh_token_families`, append-only `tenant_audit_log`, `event_publication`).
- `tenancy` module: `TenantContext` + `TenantContextTaskDecorator`, bounded LRU `TenantDataSourceRegistry` with PgBouncer transaction-pool support, `TenantProvisioningService` with ShedLock recovery job, and `TenantMigrationOrchestrator`.
- `identity` module: Argon2id password/PIN hashing, Bucket4j+Redis PIN rate limiting + lockout, RS256 JWT with `kid` JWKS rotation, rotating refresh token family reuse detection, web cookie + mobile bearer/device flows, and TOTP MFA setup/enable hooks for `ADMIN`+.
- `audit` module: SHA-256 hash-chained append-only writer for `platform_audit_log` and `tenant_audit_log`.
- Backend verification suites: `ModulithStructureTest`, `ArchitectureRulesTest`, `TenantIsolationIntegrationTest`, `AuthSecurityIntegrationTest`, `OpenApiExportTest`.
- OpenAPI 3.1 contract (`/docs/openapi.yaml`), generated TypeScript client (`/packages/api-client`), Paystack-inspired design tokens (`/packages/ui`), and P0 PWA auth/session shell (`/apps/web`).
- Architecture Decision Records `ADR-001`, `ADR-002`, `ADR-003`, `ADR-006`, `ADR-010`.
