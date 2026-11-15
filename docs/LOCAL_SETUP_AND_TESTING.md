# Proofly — Local Setup & End-to-End Testing Guide

This guide walks through running the full Proofly stack locally (PostgreSQL 16 + PostGIS, PgBouncer, Redis 7, Mailpit, Spring Boot 3.4 API, and React Vite PWA) and verifying every flow end-to-end with **zero hardcoded secrets**.

---

## 1. Prerequisites

| Tool | Minimum Version | Purpose |
| :--- | :--- | :--- |
| **Java (JDK)** | 21 | Spring Boot backend (`backend/`) |
| **Maven** | 3.9+ | Build, Flyway/Testcontainers tests, Spring Modulith & ArchUnit |
| **Docker & Compose** | Docker 24+ | Local PostGIS, PgBouncer, Redis, Mailpit (`infra/docker-compose.yml`) |
| **Node.js & npm** | Node 22+ | Frontend PWA (`apps/web`), Landing (`apps/marketing`), TS unit tests |
| **OpenSSL / curl** | Any | Generating 32-byte AES key & signing local Paystack test webhooks |

---

## 2. Environment Configuration (`application.properties` + `.env`)

All backend URLs, database credentials, token TTLs, SMTP settings, payment gateway routing (`Paystack` for `NGN`/`GHS`/`KES`, `Stripe` for `USD`/`GBP`), and scheduled job thresholds in `/backend/src/main/resources/application.properties` are bound to environment variables (`${ENV_VAR:default}`).

1. Copy `.env.example` to `.env` in the repository root:
   ```bash
   cp .env.example .env
   ```
2. Generate a real 32-byte AES-256-GCM key for `PROOFLY_PII_KEY_BASE64`:
   ```bash
   openssl rand -base64 32
   ```
3. Set local-development values in `.env`:
   - `PROOFLY_CONTROL_DB_PASSWORD` / `PROOFLY_TENANT_DB_PASSWORD`: match `POSTGRES_PASSWORD` in `/infra/docker-compose.yml` (`proofly_local_dev_password` by default for local Docker).
   - `PROOFLY_COOKIE_SECURE="false"` (required for `http://localhost:3000`; keep `true` in HTTPS production).
   - `PAYSTACK_SECRET_KEY` and `PAYSTACK_WEBHOOK_SECRET`: your Paystack test secret (`sk_test_...`) for `NGN` / `GHS` / `KES`.
   - `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET`: your Stripe test key (`sk_test_...`) and webhook signing secret (`whsec_...`) for `USD` / `GBP`.
   - Optional job & gateway overrides: `PROOFLY_PAYSTACK_CURRENCIES`, `PROOFLY_STRIPE_CURRENCIES`, `PROOFLY_NOTIFY_PROCESSING_TIMEOUT_SEC`, `PROOFLY_BILLING_PENDING_SWEEP_MIN_AGE_MIN`, `PROOFLY_PROVISIONING_MAX_RETRIES`, `PROOFLY_TENANCY_DRIFT_CHECK_ENABLED`.
4. Export `.env` into your shell before starting the backend:
   ```bash
   set -a && source .env && set +a
   ```

---

## 3. Start Local Infrastructure

```bash
docker compose -f infra/docker-compose.yml up -d
```

Services started:
- **PostgreSQL 16 + PostGIS 3.4**: `localhost:5432` (control DB `proofly_control`)
- **PgBouncer**: `localhost:6432` (optional pooled tenant routing when `PROOFLY_USE_PGBOUNCER=true`)
- **Redis 7.4**: `localhost:6379` (rate limiting + JWT `jti` denylist)
- **Mailpit SMTP & Web UI**: SMTP on `localhost:1025`, Web inbox at `http://localhost:8025`

Verify containers are healthy:
```bash
docker compose -f infra/docker-compose.yml ps
```

---

## 4. Start the Backend & Frontend

### Terminal 1 — Spring Boot API (`http://localhost:8080`)
```bash
set -a && source .env && set +a
cd backend
mvn spring-boot:run
```
- Flyway automatically migrates `db/migration/control` (`V1`–`V4`, 4 plans, multi-currency gateway pricing, and exact-currency subscription metadata) and provisions each tenant schema at `db/migration/tenant` (`V1`, `V2` — PostGIS geofenced sites, multi-site assignments, attendance events, corrections, and leave requests).
- Swagger UI / OpenAPI 3.1 docs are available at `http://localhost:8080/docs`.

### Terminal 2 — React PWA & Landing Page (`http://localhost:3000`)
```bash
npm install
npm run dev
```
- Landing page: `http://localhost:3000/`
- Sign up: `http://localhost:3000/signup`
- Sign in: `http://localhost:3000/login`
- Workspace portal: `http://localhost:3000/workspace`

Vite proxies `/api/*` directly to `http://localhost:8080`.

---

## 5. Run Automated Test Suites

### Frontend & Shared Logic Unit Tests
```bash
npx tsx --test \
  packages/api-client/src/client.test.ts \
  packages/ui/src/pricing.test.ts \
  apps/web/src/lib/validation.test.ts \
  apps/web/src/lib/roleHelpers.test.ts
```

### Backend Unit, Architecture & Testcontainers Integration Tests
Requires Docker running on the host:
```bash
cd backend
mvn verify
```
Covers:
- `ModulithStructureTest` & `ArchitectureRulesTest` (module boundaries & layer rules)
- `PaymentGatewayRouterUnitTest` (gateway routing across NGN/GHS/KES vs USD/GBP + unknown currency error handling)
- `TenantIsolationIntegrationTest` (two tenant DBs, cross-tenant rejection, async context propagation)
- `AuthSecurityIntegrationTest` (Argon2id, RS256 JWKS, refresh reuse detection, PIN lockout, TOTP MFA)
- `RecoveryAndNotificationIntegrationTest` (verification & password-reset tokens, session revocation, outbox dispatch, stuck `PROCESSING` recovery)
- `LedgerAndWebhookIntegrationTest` (Paystack HMAC-SHA512 + Stripe timestamped HMAC-SHA256 verification, idempotent replay, currency-isolated double-entry ledger invariants)
- `RoleOperationsIntegrationTest` (site geofence CRUD, multi-site shift overrides, out-of-fence `PENDING_REVIEW` flagging, leave routing, who/when/why corrections, payroll CSV, role access guards, and zero-FX USD subscription balance isolation)

---

## 6. End-to-End Manual Testing Recipes

### A. Organization Signup & Email Verification (Mailpit)
1. Open `http://localhost:3000/signup` and create an organization (e.g. `Apapa Logistics`, slug `apapa-logistics`).
2. Confirm you land on `/workspace` with your `SUPER_ADMIN` session and tenant DB `proofly_t_<uuid>` active.
3. Open Mailpit at `http://localhost:8025`. You will see **"Verify your Proofly email for Apapa Logistics"**.
4. Click **Verify Email Address** (or copy the `/verify-email?tid=...&token=...` link).
5. Verify the frontend calls `POST /api/v1/auth/verify-email/confirm` and displays confirmation.
6. Test the Workspace **Notification Outbox** tab (`http://localhost:3000/workspace?tab=outbox`) to confirm the delivery transitioned to `SENT`.

### B. Password Reset & Session Revocation
1. Open `http://localhost:3000/login` and click **Forgot password?**.
2. Submit your owner email (`POST /api/v1/auth/password-reset/request` returns `202 Accepted`).
3. Open Mailpit (`http://localhost:8025`) and click **Reset Password**.
4. Enter and confirm a new 10+ character password on `/reset-password`.
5. Verify prior refresh sessions are revoked (attempting to use the old refresh cookie returns `401`), then sign in with your new password.

### C. Multi-Currency Gateway Checkout, Local Webhook Simulation & Ledger Check

Proofly routes checkouts automatically by currency via `PaymentGatewayRouter`:
- **Paystack** handles `NGN`, `GHS`, `KES` (`/api/v1/webhooks/paystack`)
- **Stripe** handles `USD`, `GBP` (`/api/v1/webhooks/stripe`)

#### Option 1 — Paystack (`NGN`, Growth Annual = ₦313,200 = `31320000` kobo)
1. Sign in as `SUPER_ADMIN` and open `http://localhost:3000/workspace?tab=billing`.
2. Select **Growth** (`GROWTH`), **Annual (10% off)**, and currency **NGN**, enter your billing email, and click **Pay with Paystack**.
3. Simulate Paystack's `charge.success` webhook locally using your `PAYSTACK_WEBHOOK_SECRET`:
   ```bash
   REF="prf_replace_with_checkout_ref"
   AMOUNT_MINOR=31320000
   SECRET="${PAYSTACK_WEBHOOK_SECRET}"

   BODY=$(cat <<JSON
   {
     "event": "charge.success",
     "data": {
       "id": 9001001,
       "reference": "${REF}",
       "amount": ${AMOUNT_MINOR},
       "currency": "NGN",
       "status": "success",
       "customer": { "email": "admin@apapa.ng" }
     }
   }
   JSON
   )

   SIG=$(printf '%s' "$BODY" | openssl dgst -sha512 -hmac "$SECRET" | awk '{print $2}')

   curl -i -X POST http://localhost:8080/api/v1/webhooks/paystack \
     -H "Content-Type: application/json" \
     -H "x-paystack-signature: ${SIG}" \
     -d "$BODY"
   ```

#### Option 2 — Stripe (`USD`, Growth Annual = $259.20 = `25920` cents)
1. In `http://localhost:3000/workspace?tab=billing`, switch currency to **USD**, select **Growth** + **Annual (10% off)**, and click **Pay with Stripe**.
2. Simulate Stripe's `checkout.session.completed` webhook using `STRIPE_WEBHOOK_SECRET` (replay-checked `t=<epoch>,v1=<hmac_sha256>`):
   ```bash
   REF="prf_replace_with_checkout_ref"
   AMOUNT_MINOR=25920
   SECRET="${STRIPE_WEBHOOK_SECRET}"
   TS=$(date +%s)

   BODY=$(cat <<JSON
   {
     "id": "evt_local_1001",
     "type": "checkout.session.completed",
     "data": {
       "object": {
         "id": "cs_test_local_1001",
         "client_reference_id": "${REF}",
         "amount_total": ${AMOUNT_MINOR},
         "currency": "usd",
         "payment_status": "paid",
         "metadata": { "proofly_reference": "${REF}" }
       }
     }
   }
   JSON
   )

   V1=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')

   curl -i -X POST http://localhost:8080/api/v1/webhooks/stripe \
     -H "Content-Type: application/json" \
     -H "Stripe-Signature: t=${TS},v1=${V1}" \
     -d "$BODY"
   ```

3. Open `http://localhost:3000/billing/callback?reference=${REF}` (or refresh the Billing tab in `/workspace`):
   - `GET /api/v1/billing/verify` returns `localStatus: "SUCCEEDED"` and records the gateway (`PAYSTACK` or `STRIPE`).
   - Mailpit (`http://localhost:8025`) receives **"Proofly payment receipt — Growth"**.
4. Inspect the currency-isolated double-entry journal in PostgreSQL:
   ```bash
   docker exec -it $(docker ps -qf "name=postgres") \
     psql -U proofly_admin -d proofly_control \
     -c "SELECT j.entry_type, a.code, l.direction, l.amount_minor, l.currency
         FROM ledger_lines l
         JOIN journal_entries j ON j.id = l.journal_entry_id
         JOIN ledger_accounts a ON a.id = l.account_id
         ORDER BY l.created_at DESC LIMIT 6;"
   ```
   You will see balanced `DEBIT` = `CREDIT` rows across `platform:clearing:<GATEWAY>:<CURRENCY>` (e.g. `platform:clearing:PAYSTACK:NGN` or `platform:clearing:STRIPE:USD`), `tenant:<id>:credit:<CURRENCY>`, and `platform:revenue:subscriptions:<CURRENCY>`.

### D. Dead-Letter Notification Replay
1. Temporarily stop Mailpit (`docker compose -f infra/docker-compose.yml stop mailpit`).
2. Trigger a password reset or click **Resend verify email** in `/workspace`.
3. Wait for `attempt_count` to hit `max_attempts` (`DEAD_LETTER`) or inspect status `FAILED` in `/workspace?tab=outbox`.
4. Start Mailpit again (`docker compose -f infra/docker-compose.yml start mailpit`) and click **Retry** on the Notification Outbox tab (`POST /api/v1/notifications/outbox/{id}/retry`).
5. Confirm the email arrives in Mailpit and status turns `SENT`.

### E. Scheduled Jobs & Automated Self-Healing Sweeps
All ShedLock-coordinated jobs bind explicitly to `controlPlaneTransactionManager` / `controlPlaneTransactionTemplate`:
- **Outbox Dispatch & Stuck Recovery** (`NotificationDispatcherJob`): dispatches `PENDING` rows and automatically resets stale `PROCESSING` rows older than `PROOFLY_NOTIFY_PROCESSING_TIMEOUT_SEC` (default `120s`) after process crashes.
- **Pending Checkout Reconciliation** (`reconcilePendingCheckouts`): polls gateways for stale `INITIATED` checkouts to settle missed webhooks or mark expired sessions `ABANDONED`.
- **Dunning & Suspension** (`processOverdueDunning`): atomically suspends overdue subscriptions, writes `platform_audit_log`, and queues `SUBSCRIPTION_SUSPENDED` alerts.
- **Ledger Integrity & Checkpoints** (`reconcileAccountBalances`): validates `account_balances` vs `ledger_lines`, opens deduplicated `reconciliation_issues`, and advances `balance_checkpoints`.
- **Tenant Schema Drift & Provisioning Recovery**: `TenantProvisioningService` retries incomplete tenant provisioning within `PROOFLY_PROVISIONING_MAX_RETRIES`, and `TenantMigrationOrchestrator.checkTenantSchemaDrift()` flags schema version drift on `ACTIVE` tenants.

### F. Role-Based Operations Walkthrough (`SUPER_ADMIN` → `ADMIN` → `MANAGER` → `EMPLOYEE`)

1. **Sites & Staff Setup (`SUPER_ADMIN` / `ADMIN`)**:
   - Open `http://localhost:3000/workspace?tab=sites`, create a geofenced site (e.g., `Apapa Terminal`, `6.4501`, `3.3622`, `150m` radius, `08:00` shift start, `15m` grace, `480m` OT threshold), and adjust Organization Defaults.
   - Open `http://localhost:3000/workspace?tab=staff`, invite a **Manager** via **Work Email** (accept link in Mailpit at `/accept-invite?tid=...&token=...`), and onboard field **Staff** via **Phone + PIN** (`+2348011122233` + 4–8 digit PIN) assigned to that manager.
   - Click **Sites** on each staff row to assign multiple sites with optional per-site shift start, grace, or overtime overrides.
2. **Personal Clock-In, Out-of-Fence Flagging & Leave (`EMPLOYEE`)**:
   - Sign in on `/login` using **Phone + PIN** (or accepted email account). Verify only `My Shift & Leave` and `Devices & Security` tabs appear.
   - On `My Shift & Leave`, capture live GPS (or toggle **Manual GPS** with coordinates outside the 150m site radius), then click **Clock In Now**.
   - Verify the punch is saved (`201 Created`), marked `PENDING_REVIEW` with PostGIS distance in meters, and queued in Mailpit for the assigned manager.
   - Submit an attendance correction (`Correct` button) with a timestamp and reason, plus a leave request.
3. **Live Site Board, Review Queue & Payroll CSV (`MANAGER`+)**:
   - Sign in as the **Manager** and open `Live Site Board` (`?tab=team`) to inspect direct reports' `ON_SITE`, `LATE`, `PENDING_REVIEW`, `ON_LEAVE`, and `OFF` counts.
   - Open `Review Queue` (`?tab=reviews`) and approve/reject the flagged out-of-fence punch, pending leave request, and timestamp correction with a written audit reason.
   - Open `Payroll & CSV` (`?tab=payroll`), preview regular/late/overtime hours, and click **Download Payroll CSV**.
4. **Zero-FX Billing & Immutable Audit Trail (`ADMIN` / `SUPER_ADMIN`)**:
   - Complete a `USD` Stripe test checkout (`Option 2` above). Open `?tab=billing` and confirm `Settled currency & gateway` shows `USD · Stripe`, `Per-Currency Ledger Accounts` lists `USD` separately from `NGN` with zero currency conversion, and the Payment Receipts table records the exact `$259.20` (`25920` cents) settlement.
   - Open `?tab=audit` to inspect the SHA-256 hash-chained `tenant_audit_log` entries (`rowHash`, `beforeStateJson`, `afterStateJson`, `requestId`).

