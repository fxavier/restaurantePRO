# 03: Dev and test realms: BackOffice dev origin and an e2e user with a known OTP (G15, G16)

**Status:** ready-for-agent
**Blocked by:** None (can start immediately)
**Owner:** `developer` (backend/deploy). Spec: PRD AC1–AC2, [ADR 0043](../../adr/0043-frontend-ci-and-real-stack-e2e.md).

## What to build

Make the dev Keycloak usable by the frontend. The BackOffice dev origin becomes a redirect URI and web origin of `backoffice-bff` in the dev realm only. The e2e seed provides a tenant administrator with an OTP credential whose secret the Playwright tests know, created by a seed step for dev and test environments only, never through the committed realm export and never in production.

## Acceptance criteria

- [ ] A sign-in started from the BackOffice dev origin returns to it after login.
- [ ] The seed is idempotent and refuses to run against a non-dev issuer.
- [ ] A test signs the seeded user in with password and a generated TOTP code and gets `acr` 2.
- [ ] No secret is committed in the realm exports. The seed reads the OTP secret from the e2e environment.
- [ ] `deploy/keycloak/README.md` documents both.
