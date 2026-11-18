# 0042. Typed API client: openapi-typescript and openapi-fetch over a committed OpenAPI snapshot

Status: **Accepted** 2026-11-18 (user approval, frontend R1 Phase 2 gate; frontend R1 alignment, round R5). Source: [frontend-conventions.md](../architecture/frontend-conventions.md) §7–§9; `.claude/agents/frontend-developer.md` ("a typed client generated from the backend's OpenAPI spec", no axios); spec §9.

## Context

The agent files fix "a typed client generated from the backend's OpenAPI spec" but not the generator. The backend publishes springdoc at `/v3/api-docs` only behind a bearer token (open with `restaurantpro.dev.open-api-docs=true` in development), with fully qualified schema names (`springdoc.use-fqn`), and no snapshot is committed. The client must add per-app headers (POS bearer and `X-Pos-Session`; BackOffice CSRF), send an `Idempotency-Key` per user intent, and parse problem+json, while TanStack Query owns caching and invalidation (SSE invalidates query keys).

## Decision

- **`openapi-typescript`** generates `schema.d.ts` (types only) from **`frontend/packages/api-client/openapi.json`**, a committed snapshot of the backend contract.
- **`openapi-fetch`** is the runtime client: typed paths, params and bodies over `fetch`, with middleware for the per-app headers and one `ApiError` mapping.
- Features write their own small TanStack Query hooks over the client; no generated hooks.
- The snapshot is refreshed by a backend export (gap G1: a test or Maven step that writes the OpenAPI document without a running server); CI fails when the committed snapshot differs from the export.

## Alternatives considered

- **orval:** generates TanStack Query hooks and MSW mocks as well, but its generated hooks fix query keys, retry and invalidation in ways that conflict with SSE invalidation and per-intent idempotency keys, and its output is large.
- **Fetching `/v3/api-docs` from a running backend at build time:** no committed file, but builds and CI then depend on a running backend with documentation opened.
- **Hand-written types:** no tooling, but they drift silently from the contract.

## Consequences

- Two dependencies in `packages/api-client` (one dev). No axios, no generated runtime code.
- A backend change to a DTO shows up as a snapshot diff in the backend PR and as a type error in the frontend.
- MSW handlers are typed from the same `schema.d.ts`; a handler for an endpoint that does not exist yet is marked as a gap.
