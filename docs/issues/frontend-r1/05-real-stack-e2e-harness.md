# 05: Real-stack e2e harness and frontend hosting (G14)

**Status:** ready-for-agent
**Blocked by:** 03, 04
**Owner:** `qa-engineer`, with `developer` for deploy. Spec: [ADR 0041](../../adr/0041-frontend-origins-and-pos-delivery.md), [ADR 0043](../../adr/0043-frontend-ci-and-real-stack-e2e.md), FC §3, §14.

## What to build

A compose setup that serves the BackOffice SPA on the BFF's origin and the POS SPA behind a reverse proxy to the backend (the ADR 0041 routing), together with PostgreSQL, Keycloak with the dev realms and the 03 seed, the backend and the BFF. A Playwright project points at those origins. A seeding helper creates a tenant, a store, a waiter with a PIN and a published catalog through the public API and provisioners, never SQL. The CI workflow gains the e2e job.

## Acceptance criteria

- [ ] Both smoke tests from 04 pass through the real origins.
- [ ] A seeded BackOffice user signs in with OTP and sees the shell placeholder, which proves the BFF cookies and CSRF over the proxy.
- [ ] The POS origin forwards only `/api/v1/pos/**` and `/api/v1/auth/**`. Any other API path answers 404 there.
- [ ] The seed data is unique per run, so tests do not depend on each other.
- [ ] The e2e job runs in CI and uploads the Playwright report on failure.
- [ ] The production deploy docs describe the two origins and their routing.
