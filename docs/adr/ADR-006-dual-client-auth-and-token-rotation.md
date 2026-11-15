# ADR-006: Dual-Client Authentication & Rotating Refresh Token Families

- **Status:** Accepted
- **Date:** 2026-11-14

## Context
Proofly must serve both an installable Web PWA and future iOS/Android/API clients from one identical API surface without cookie-only assumptions or weak session handling.

## Decision
1. **Argon2id** (64 MiB, 3 iterations) for both passwords and staff PINs; PIN login adds Redis Bucket4j rate limiting + progressive DB lockout (`pin_locked_until`).
2. **Short-lived asymmetric RS256 JWTs** ($\le 15\text{ min}$) with `kid` header rotation and `/.well-known/jwks.json`; logout/revocation adds `jti` to a Redis denylist.
3. **Rotating Refresh Token Families (`refresh_token_families`)**: presenting any previously rotated token triggers `reuse_detected_at` and immediately revokes the entire family.
4. **Dual-Client Transport**:
   - `WEB`: `HttpOnly; Secure; SameSite=Lax` cookie + `Origin` validation.
   - `MOBILE`/`API`: Bearer refresh token in JSON bound to `devices` (`DELETE /api/v1/devices/{id}` revokes all families for that device).
5. **MFA Ready**: `MfaService` provides RFC 6238 TOTP enrollment and AES-256-GCM encrypted secrets on `users` for `ADMIN` and `SUPER_ADMIN`.
