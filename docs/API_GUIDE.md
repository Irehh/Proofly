# Proofly API Guide (v1 — P0)

## 1. Base URL & Versioning
- All versioned endpoints sit under `/api/v1/...`.
- Breaking changes require a new major version (`/api/v2/...`) with a minimum 180-day deprecation window communicated via `Deprecation` and `Sunset` HTTP headers.
- Contract changes are gated in CI by `oasdiff` against `/docs/openapi.yaml`.

## 2. Correlation IDs
- Pass `X-Request-Id` on every request (or let the gateway generate a UUID).
- The server echoes `X-Request-Id`, binds it to structured logs (`corrId`), and embeds it in every audit row and RFC 7807 error payload.

## 3. Dual-Client Authentication
Proofly exposes one unified API for both the Web PWA and Mobile/API clients:

| Client Type | `clientType` | Refresh Token Delivery | CSRF Protection |
| :--- | :--- | :--- | :--- |
| **Web PWA** | `WEB` | `HttpOnly; Secure; SameSite=Lax` cookie (`proofly_refresh`) | `Origin` allow-list verification on cookie refresh/logout |
| **Mobile / API** | `MOBILE` / `API` | JSON `refreshToken` field bound to `devices.id` | Bearer refresh + per-device revocation (`DELETE /api/v1/devices/{id}`) |

- **Access tokens**: Asymmetric `RS256` JWTs ($\le 15\text{ min}$ TTL) carrying `sub`, `tid` (tenant UUID), `mid` (membership UUID), `role`, and `jti`. Public keys are published at `GET /.well-known/jwks.json` with `kid` rotation.
- **Tenant Isolation**: `TenantContext` is derived *exclusively* from the verified JWT `tid` claim. Any client-supplied `X-Tenant-ID` header is ignored.
- **Refresh Rotation & Reuse Detection**: Every `/api/v1/auth/refresh` rotates the opaque secret inside `refresh_token_families`. Presenting an already-rotated token immediately revokes the entire token family (`PROOFLY_REFRESH_TOKEN_REUSED`).
- **Phone + PIN Lockout**: Staff PIN logins are protected by Redis Bucket4j rate limiting and lock after 5 consecutive failures (`PROOFLY_PIN_LOCKED`, HTTP `423`).

## 4. Errors (RFC 7807 `application/problem+json`)
All error responses return RFC 7807 Problem Details with stable `errorCode` values:
- `PROOFLY_VALIDATION_ERROR` (`422`)
- `PROOFLY_INVALID_CREDENTIALS` (`401`)
- `PROOFLY_PIN_LOCKED` (`423`)
- `PROOFLY_RATE_LIMITED` (`429`)
- `PROOFLY_REFRESH_TOKEN_REUSED` (`401`)
- `PROOFLY_TOKEN_REVOKED` (`401`)
- `PROOFLY_TENANT_CONTEXT_MISSING` (`401`)
- `PROOFLY_TENANT_NOT_ACTIVE` (`403`)

## 5. Conventions for P1+ Endpoints
- **Idempotency**: Unsafe `POST` endpoints accept `Idempotency-Key` (UUID v4).
- **Optimistic Concurrency**: Mutable resources emit `ETag` and enforce `If-Match`.
- **Pagination**: Cursor-based pagination (`?cursor=...&limit=50`) with ISO-8601 UTC timestamps.
