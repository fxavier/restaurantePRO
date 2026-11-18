# 01: Backend exports the API contract without a running server (G1)

**Status:** ready-for-agent
**Blocked by:** None (can start immediately)
**Owner:** `developer` (backend). Spec: [frontend-r1 PRD](../../prd/frontend-r1.md), [ADR 0042](../../adr/0042-typed-api-client-from-openapi-snapshot.md), FC §7.

## What to build

A backend build step (a test on the Spring context, or a Maven step) writes the springdoc OpenAPI document of every `/api/v1/**` route to the API-client package's committed snapshot, without starting a server and without opening the docs in any deployed profile. A check fails when the committed snapshot differs from what the code produces, so a DTO change shows up as a snapshot diff in the same PR.

## Acceptance criteria

- [ ] One command, run from `backend/`, writes the snapshot. The command is documented in the backend's and the API-client's README.
- [ ] The snapshot covers the POS, BackOffice, auth, bridge and platform route families, including the `Idempotency-Key`, `X-Pos-Session`, `X-Approver-Code` and `X-Approver-Pin` headers as declared parameters where the controllers read them.
- [ ] `/v3/api-docs` stays closed in every non-dev profile (existing security tests still pass).
- [ ] The drift check fails, with a readable message, when the snapshot is stale. A test proves it red first.
- [ ] The full backend suite passes (`rtk mvn clean test`, 0 failures, 0 errors, 0 skipped).
