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

## [P1] Landing Page, Transactional Notification Outbox, Recovery & Paystack Double-Entry Ledger
### Added
- Env-driven `application.properties` eliminating hardcoded URLs, credentials, SMTP/SMS settings, token TTLs, and Paystack keys (`${ENV_VAR:default}`).
- Comprehensive `notification` module: control-plane `notification_outbox` (`V2__notifications_and_billing_ledger.sql`), HTML/text `NotificationTemplateEngine`, SMTP/Mailpit `EmailSender`, `SmsSender`, ShedLock `NotificationDispatcherJob` with `FOR UPDATE SKIP LOCKED` and exponential backoff, and admin outbox list/retry endpoints.
- Account recovery & verification: `AccountRecoveryService` + `V2__verification_and_password_reset.sql` (`verification_tokens` SHA-256 hashed, single-use email verify + password reset + active session revocation + `/api/v1/auth/verify-email/resend`).
- `billing` module: double-entry PostgreSQL ledger (`ledger_accounts`, `journal_entries`, `ledger_lines`, `account_balances` with append-only & deferred balance triggers), `PaystackClient` (Resilience4j), HMAC-SHA512 webhook verification, idempotent checkout, payment verification, and dunning/reconciliation jobs.
- Public Landing Page (`/apps/marketing/src/LandingPage.tsx`) with interactive PostGIS geofence simulator, alternating capability cards, interactive Monthly/Annual (10% off) + multi-currency pricing, FAQ accordion, demo booking form, and Privacy/Terms/DPA pages.
- Frontend `/forgot-password`, `/reset-password`, `/verify-email`, `/billing/callback`, and Workspace Billing & Notification Outbox management tabs.
- [`docs/LOCAL_SETUP_AND_TESTING.md`](./LOCAL_SETUP_AND_TESTING.md) and root [`README.md`](../README.md).

## [P2] Multi-Currency Gateway Routing, 4-Tier Pricing & Hardened Jobs
### Added & Changed
- **4 Simpler, Lower-Priced Plans (`V3__multi_currency_gateways_and_4_plans.sql`)**: Consolidated from 6 tiers to `FREE` (4 staff / 1 site, ₦0), `STARTER` (25 staff / 3 sites, ₦9,000 / $8), `GROWTH` (100 staff / 15 sites, ₦29,000 / $24), and `ENTERPRISE` (5,000 staff / 500 sites, ₦95,000 / $79) with 10% annual discount across `NGN`, `USD`, `GBP`, `KES`, and `GHS`; migrated legacy `TEAM`/`BUSINESS` subscriptions safely.
- **Gateway-Routed Multi-Currency Payments**: Introduced `PaymentGateway`, `GatewayCode`, `PaymentGatewayRouter`, `PaystackGatewayAdapter` (`NGN`, `GHS`, `KES`), and `StripeGatewayAdapter` (`USD`, `GBP`, Stripe Checkout Sessions + timestamped HMAC-SHA256 `Stripe-Signature` verification at `/api/v1/webhooks/stripe`), with currency-isolated double-entry ledger accounts (`platform:clearing:{GATEWAY}:{CURRENCY}`).
- **Background Job & Transaction-Manager Hardening**:
  - Added explicit `controlPlaneTransactionManager` / `controlPlaneTransactionTemplate` in `DataSourceConfig` so control-plane services and ShedLock jobs never depend on `@Primary` tenant routing.
  - Replaced `@Transactional` self-invocation in `NotificationDispatcherJob` and added `recoverStuckProcessing()` watchdog for crashed `PROCESSING` outbox rows.
  - Added `BillingDunningAndReconciliationJob.reconcilePendingCheckouts()` sweep to verify missed webhooks or expire stale checkouts, atomic `AuditWriter` + `SUBSCRIPTION_SUSPENDED` dunning alerts, and deduplicated `reconciliation_issues` + `balance_checkpoints` advancement.
  - Added configurable retry ceilings (`PROOFLY_PROVISIONING_MAX_RETRIES`) and `TenantMigrationOrchestrator.checkTenantSchemaDrift()`.
- **UI & API Client Updates**: Updated OpenAPI 3.1 contract (`/docs/openapi.yaml`), `@proofly/api-client`, `@proofly/ui` pricing, Landing Page 4-card pricing grid with live Paystack/Stripe settlement routing, Signup currency forwarding, Billing Callback, and Workspace Billing currency selector.

## [P3] Role-Based Dashboards, Geofenced Operations & Zero-FX Billing Isolation
### Added & Changed
- **5 New Acyclic Backend Modules**:
  - `organization`: org attendance defaults, role-scoped member roster, `EMAIL` invite flow (`/api/v1/org/invites/accept`), instant active `PHONE_PIN` field staff onboarding, plan member quota checks, and `SUPER_ADMIN` governance guards.
  - `site`: PostGIS `GEOGRAPHY(Point, 4326)` geofenced sites CRUD, plan site quota checks, `ST_Distance` computation, and multi-site assignments with per-site shift start, late grace, and overtime threshold overrides (`db/migration/tenant/V2__sites_attendance_leave.sql`).
  - `attendance`: live-connection geofenced clock-in/out, automatic `PENDING_REVIEW` flagging for out-of-fence or low-accuracy punches (never silently dropped), site-timezone late/worked/overtime math, and who/when/why timestamp corrections.
  - `leave`: manager-routed leave requests with approve/reject/cancel workflow and notification templates.
  - `reporting` & `audit`: real-time team presence board, payroll summary + CSV-formula-safe export, and SHA-256 hash-chained tenant audit log endpoint.
- **Zero-FX-Conversion Multi-Currency Billing (`V4__subscription_currency_metadata.sql`)**:
  - Persists exact `currency` and `gateway` on `subscriptions`, exposes isolated per-currency ledger credit balances (`currencyBalances`) and payment receipts (`GET /api/v1/billing/payments`), and keeps `USD` payments strictly in `USD` without conversion.
- **Unified Role-Aware Workspace PWA (`/apps/web`)**:
  - Implemented `MyShiftPanel` (`EMPLOYEE`+), `TeamOperationsPanel` (`MANAGER`+), `SitesAndStaffPanel` (`ADMIN`+), `BillingAndGovernancePanel` (`ADMIN`+), `OverviewSecurityPanel`, and `/accept-invite` (`AcceptInvitePage`), backed by pure role/validation helpers (`roleHelpers.ts`, `validation.ts`) and expanded unit & Testcontainers integration suites (`RoleOperationsIntegrationTest`).


