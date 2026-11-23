# 0043. Frontend CI on GitHub Actions; Playwright end-to-end tests against the real stack

Status: **Accepted** 2026-11-18 (user approval, frontend R1 Phase 2 gate; frontend R1 alignment, round R5). **Amended 2026-11-23 (user decision):** the backend suite now runs in CI, in its own workflow `backend.yml` (see Consequences). Source: [frontend-conventions.md](../architecture/frontend-conventions.md) §14–§15; spec §8.5, §12, §14; `.claude/agents/qa-engineer.md`.

## Context

The repository has no CI (`.github/modernize/` holds recording hooks, not CI). The R1 flows depend on real authentication (Keycloak devices realm with a browser-signed assertion, the BFF with MFA and step-up), real idempotency and real SSE; §14 #2 asks that two terminals see each other's confirmed changes. A mocked end-to-end test cannot prove any of that.

## Decision

- **GitHub Actions** workflow `frontend.yml`, on pull requests touching `frontend/**` or the OpenAPI snapshot: `pnpm install --frozen-lockfile`, lint, typecheck, Vitest, build for each package and app, the OpenAPI snapshot drift check (ADR 0042), then the e2e job.
- **Playwright runs against the real stack** started from compose: PostgreSQL, Keycloak with the dev realms, the backend, the BFF, and both apps built and served behind their origins (ADR 0041). Test data is seeded through the public API and the provisioners, never through SQL.
- Component and flow tests (Vitest + Testing Library) use MSW; they cover loading, empty, error, unavailable, 409 and permission states.

## Alternatives considered

- **Playwright against MSW in the browser:** fast and hermetic, but it never proves the authentication flows or the contract.
- **Real-stack e2e run locally only:** no CI cost, but nothing stops a regression from merging.

## Consequences

- A first CI workflow in the repository; the e2e job takes minutes and needs Docker on the runner.
- The BackOffice e2e needs a test user with OTP: the dev realm or the test seeds provide a user whose TOTP secret is known to the test (gap G16), never a production realm.
- The backend's own suite is not run by this workflow. **Amended 2026-11-23:** it runs in `.github/workflows/backend.yml` (`./mvnw verify`, Testcontainers on the hosted runner's Docker) on every pull request and push to main that touches `backend/**`. The gap showed when a migration replacing a unique constraint (FE-F35, #185) broke a test seed that only a local full run caught (#190).
