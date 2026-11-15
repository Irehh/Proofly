# ADR-010: Contract-First OpenAPI 3.1 Pipeline & oasdiff Breaking-Change Gate

- **Status:** Accepted
- **Date:** 2026-11-14

## Context
Web PWA and mobile clients require strict API stability and zero manual DTO drift.

## Decision
- `springdoc-openapi` generates OpenAPI 3.1 directly from annotated Spring controllers and exports `/docs/openapi.yaml` via `OpenApiExportTest`.
- CI fails if `/docs/openapi.yaml` drifts from the code or if `oasdiff` detects breaking changes against `main`.
- `/packages/api-client` is generated from `/docs/openapi.yaml` using `openapi-typescript` + `openapi-fetch` and is never hand-edited.
