# Proofly

**Multi-tenant geofenced staff attendance SaaS** — built with database-per-tenant PostgreSQL + PostGIS isolation, Spring Modulith, double-entry Paystack billing, transactional notification outbox, and an offline-first React PWA.

## Repository Layout

| Directory | Purpose |
| :--- | :--- |
| `/backend` | Java 21 / Spring Boot 3.4 modular monolith (`identity`, `tenancy`, `billing`, `notification`, `audit`, `common`) |
| `/apps/web` | Authenticated React + TypeScript PWA (`vite-plugin-pwa`, Workbox, TanStack Query, React Hook Form + Zod) |
| `/apps/marketing` | Public Proofly landing page, pricing calculator (NGN + multi-currency, 10% annual off), FAQ, demo booking, legal docs |
| `/packages/ui` | Shared Paystack-inspired design tokens, UI primitives, and integer minor-unit pricing/haversine utilities |
| `/packages/api-client` | Typed OpenAPI 3.1 client (`openapi-fetch` + `openapi-typescript`) |
| `/infra` | Local `docker-compose.yml` (PostGIS 16, PgBouncer, Redis 7, Mailpit) |
| `/docs` | OpenAPI 3.1 spec, ADRs, API guide, and [Local Setup & Testing Guide](./docs/LOCAL_SETUP_AND_TESTING.md) |

## Quick Start

1. Configure environment variables:
   ```bash
   cp .env.example .env
   ```
2. Start local Postgres/PostGIS, Redis, PgBouncer, and Mailpit:
   ```bash
   docker compose -f infra/docker-compose.yml up -d
   ```
3. Run the Spring Boot API (`localhost:8080`) and React PWA (`localhost:3000`):
   ```bash
   set -a && source .env && set +a
   (cd backend && mvn spring-boot:run)
   ```
   ```bash
   npm install && npm run dev
   ```

See [`docs/LOCAL_SETUP_AND_TESTING.md`](./docs/LOCAL_SETUP_AND_TESTING.md) for automated test commands, Mailpit verification, password reset testing, and local HMAC-SHA512 Paystack webhook simulation.
