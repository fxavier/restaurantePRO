# F26 — `DevSeed` (backend/dev-seed.sh) on R6

**Status:** needs-triage
**Source:** S76b note relayed by the orchestrator (2026-11-17); see also [F24](F24-load-smoke-script-r6.md)
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`deploy/load/DevSeed.java` (run by `backend/dev-seed.sh`) still logs in with the removed R5 password login (`POST /api/v1/auth/login`, first-login password change) and enrols terminals with `/api/v1/auth/terminals/enroll` and `X-Terminal-Token`, so it cannot seed an R6 dev installation. It shares `LoadSmoke.java`'s HTTP client, which has the same R5 assumptions.

An honest R6 port needs a way to obtain tokens on a dev stack, and none exists: the dev realms (`deploy/keycloak/realm-*.json`) define no users and no direct-grant client; the only tenant-realm client for people is `backoffice-bff` (authorization code, `private_key_jwt`). S76 could not port it: both routes below need paths outside S76's write scope (`deploy/keycloak/**` is S76b's; the backend's trusted keys are production configuration).

## Proposed direction

1. **Dev realm material** (owner of `deploy/keycloak/**`): a dev-only import (separate file, never in production) with an ops-realm operator holding `platform-operator`, and a public client with direct access grants enabled for the dev tool in the ops and tenant realms. Fixed, printed dev passwords, as DevSeed always had.
2. **DevSeed flow:** operator token (direct grant, ops realm) → `POST /api/v1/platform/tenants` (onboarding; the real `tenancy-provisioner` creates and invites the administrator) → set the administrator's dev password through the Keycloak admin API (dev only) → administrator token (direct grant) → the existing store, catalog and employee seed → terminals enrolled through `/api/v1/pos/enrolment` with a generated P-256 key each, writing the private JWK per terminal to `.dev-seed/` (git-ignored) for the POS dev client, which then uses `private_key_jwt` client credentials in the devices realm.
3. Rejected: a dev-profile signing key trusted by the backend (a second trust path in the production binary).
4. `deploy/load/load-smoke.sh` (F24) can then reuse the same token source.
