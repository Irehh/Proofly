# ADR-003: Spring Modulith Package-by-Feature Boundaries & ArchUnit

- **Status:** Accepted
- **Date:** 2026-11-14

## Context
Rapid SaaS iteration often degrades layered monoliths into tightly coupled "big balls of mud" where attendance, billing, and auth reach directly into each other's tables.

## Decision
- Organize backend into explicit Spring Modulith modules (`common`, `tenancy`, `identity`, `organization`, `site`, `attendance`, `leave`, `reporting`, `billing`, `audit`, `notification`).
- Modules only depend on declared public interfaces/events (`@ApplicationModule(allowedDependencies = ...)`).
- Enforce boundaries on every CI run using `ModulithStructureTest` (`ApplicationModules.verify()`) and `ArchitectureRulesTest` (ArchUnit).
- Persist cross-module events in `event_publication` for reliable retry.
