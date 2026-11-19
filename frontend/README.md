# restaurantPRO frontend

The POS (FrontOffice) and the BackOffice, in one pnpm workspace. Conventions: [frontend-conventions.md](../docs/architecture/frontend-conventions.md) (FC); design: [frontend-design-system.md](../docs/design/frontend-design-system.md); decisions: ADRs [0041](../docs/adr/0041-frontend-origins-and-pos-delivery.md), [0042](../docs/adr/0042-typed-api-client-from-openapi-snapshot.md), [0043](../docs/adr/0043-frontend-ci-and-real-stack-e2e.md).

Requires Node 24 and pnpm 11 (pinned in `package.json` as `packageManager`; `corepack enable` picks it up).

## Commands (from `frontend/`)

```bash
rtk pnpm install                              # also generates the API types (packages/api-client "prepare")
rtk proxy pnpm --filter pos dev               # POS on http://localhost:5173
rtk proxy pnpm --filter backoffice dev        # BackOffice on http://localhost:5174
rtk lint                                      # ESLint over the whole workspace
rtk pnpm typecheck                            # regenerates the API types, then tsc in every package
rtk test pnpm test                            # Vitest in every package
rtk test pnpm --filter pos test -- app        # one package, one file pattern
rtk pnpm build                                # production builds of both apps (apps/*/dist)
rtk playwright test                           # Playwright smoke tests against the built apps (vite preview)
rtk err pnpm --filter api-client generate     # regenerate src/schema.d.ts from openapi.json
```

`SCREENSHOT_DIR=<dir>` makes the screenshot-taking specs write their PNGs there (named `<screen>-<width>x<height>.png`).

The first Playwright run on a machine needs the browser: `rtk proxy pnpm exec playwright install chromium`.

## Ports and proxies

| app | dev | preview (Playwright) | `vite dev` forwards |
|---|---|---|---|
| POS | 5173 | 4173 | `/api/v1/pos`, `/api/v1/auth` → backend `http://localhost:8080` (`BACKEND_URL`) |
| BackOffice | 5174 | 4174 | `/api`, `/bff`, `/oauth2`, `/login` → BFF `https://localhost:8443` (`BFF_URL`), Host kept |

Each app is same-origin with its API (ADR 0041), so the backend needs no CORS. The dev ports are fixed: the dev realm and `backend/run-local.sh` name them. `E2E_POS_URL` and `E2E_BACKOFFICE_URL` point Playwright at already-running origins instead of starting the preview servers.

## End-to-end against the real stack (ADR 0043)

`deploy/e2e/` runs PostgreSQL, Keycloak with the dev realms, the backend, the BFF and both built SPAs behind their
origins (ADR 0041; Caddy, `deploy/e2e/Caddyfile`), in a Compose project of its own (`restaurantpro-e2e`). Needs Docker,
`curl`, `jq` and `openssl`.

```bash
rtk proxy pnpm e2e:stack:up      # builds the SPAs and the images, generates this stack's secrets and keys, waits until healthy
rtk proxy pnpm e2e:stack         # Playwright against the stack: seeds this run's data, then every project
rtk proxy pnpm e2e:stack -- --project stack   # Playwright arguments go after --
rtk proxy pnpm e2e:stack:down    # removes the containers, the data and deploy/e2e/.run/<project>/
```

| origin | what |
|---|---|
| `http://localhost:14174` | BackOffice SPA + BFF |
| `http://localhost:14173` | POS SPA + backend (`/api/v1/pos/**`, `/api/v1/auth/**` only; other `/api` paths 404) |
| `http://localhost:18180` | Keycloak (admin password in `deploy/e2e/.run/restaurantpro-e2e/keycloak_admin_password`) |
| `http://localhost:18080` | backend, used by the seed only |
| `http://localhost:18025` | Mailpit (the onboarding invitations) |

All the services share one network namespace, so `localhost:18180` is Keycloak for the browser, the host and the
services alike: one issuer, and the seed's loopback-only guard stays strict. `backoffice-bff`'s `private_key_jwt` key
is generated per stack in `deploy/e2e/.run/<project>/keys/` and only its public JWK is registered (`deploy/keycloak-bff-dev-key.sh`).

**Several stacks side by side (FE-F20).** The Compose project and the five published ports come from environment
variables whose defaults are the values above; `deploy/e2e/stack.sh` is their one source (compose.yaml and the
Caddyfile require them, the POS build gets its `VITE_DEVICES_ISSUER`, Playwright its `E2E_*_URL` and the BFF's
Keycloak client its redirect URI from them). Set the same values on every command of that stack:

```bash
export E2E_PROJECT=restaurantpro-e2e-b E2E_POS_PORT=24173 E2E_BACKOFFICE_PORT=24174 \
  E2E_KEYCLOAK_PORT=28180 E2E_BACKEND_PORT=28080 E2E_MAILPIT_PORT=28025
rtk proxy pnpm e2e:stack:up && rtk proxy pnpm e2e:stack; rtk proxy pnpm e2e:stack:down
```

| variable | default |
|---|---|
| `E2E_PROJECT` | `restaurantpro-e2e` (also the `deploy/e2e/.run/<project>/` directory) |
| `E2E_POS_PORT` | `14173` |
| `E2E_BACKOFFICE_PORT` | `14174` |
| `E2E_KEYCLOAK_PORT` | `18180` |
| `E2E_BACKEND_PORT` | `18080` |
| `E2E_MAILPIT_PORT` | `18025` |

The POS, BackOffice and Keycloak ports are also listened on inside the stack's network namespace, so they cannot be
5432, 8080, 8081, 8443, 9000, 1025 or 8025 (`stack.sh` refuses them). Each stack serves its own copy of the two builds
(`.run/<project>/srv/`), so a second stack's `up` (which rebuilds `apps/*/dist` with its own Keycloak port) does not
change the first one's; start the stacks one after the other, not two `up`s at the same instant, since they share
`apps/*/dist` while building. The backend and BFF images (`restaurantpro-*:e2e`) are shared.

**Seed data, unique per run** (`e2e/global-setup.ts`, through the public API and the provisioners, never SQL): a new
tenant and its first store (platform onboarding, `deploy/keycloak-e2e-seed.sh`), its administrator in Keycloak with a
password and a TOTP secret, then, signed in through the BFF with password + TOTP: a waiter (`EMPREGADO`, random PIN),
a room with tables M1 and M2, a catalog (a plain item, an item with two variants, a dish and a menu of the two),
validated and published for the store, and three POS terminals (A, B, C) with their enrolment codes, issued there
because a terminal needs a fresh step-up (D142). The POS build of the stack gets `VITE_DEVICES_ISSUER` from
`stack.sh`. The values reach the specs through
`process.env` (`e2e/support/stack.ts`, `seeded`); nothing is written to a file. `E2E_ADMIN_TOTP_SECRET` may be set to
choose the TOTP secret; `e2e/support/totp.ts` computes the codes (RFC 6238, `node:crypto`). Keycloak refuses a code
twice, so a second sign-in in the same run waits for the next 30 s period.

**Only ever against the throwaway stack.** On failure the traces (`trace: retain-on-failure`, uploaded by CI as
the `playwright-report` artifact) hold that run's administrator password, TOTP codes and session cookies, and the POS
terminals' bearer tokens, POS session tokens and enrolment codes. Never upload or share them publicly. They are
harmless only because the stack, its users and its keys are destroyed with `e2e:stack:down`; never point
`E2E_*_URL` at a persistent or shared environment.

**Step-up in 30 s.** The stack's backend runs with `RESTAURANTPRO_SECURITY_STEPUPMAXAGE=PT30S` (D184's maximum age of
the second factor, five minutes elsewhere), so flow (a) meets a stale second factor without a five-minute wait
(`e2e/stack/terminals-step-up.spec.ts`). A spec's sensitive call more than 30 s after its sign-in goes through step-up.

The `stack` project (`e2e/stack/`) holds the specs that need the backend; the `pos` and `backoffice` smoke tests run
against both the preview servers and the stack. The `stack-serial` project (`e2e/stack-serial/`) runs last, one test at
a time, for what disturbs the whole stack: it stops and restarts the backend container, and signs in again for a
revocation. With `E2E_SCREENS=<dir>` the POS specs also save their screens at the DS viewports.

## Layout

```text
frontend/
├── apps/
│   ├── pos/                 # touch-first FrontOffice
│   └── backoffice/          # management interface
├── packages/
│   ├── ui/                  # tokens.css, Inter, shadcn components, ConnectionBanner, DataState, ConfirmButton, StatusBadge, Money
│   └── api-client/          # openapi.json snapshot, generated schema.d.ts, createApiClient + middleware, ApiError, SSE reader
├── e2e/                     # Playwright specs, one folder per app (playwright.config.ts sits here at the root)
└── eslint.config.js         # one flat config for the workspace
```

Inside an app, code goes by feature (`src/features/<module>/`), with `src/routes/` for thin pages and `src/lib/` for app setup (FC §2).

## API client

`packages/api-client/openapi.json` is the committed contract snapshot; never edit it by hand (see its README). `src/schema.d.ts` is generated from it and is not committed: `pnpm install` and `pnpm typecheck` both regenerate it, so it cannot drift from the snapshot.

```ts
const client = createApiClient({ middleware: [posMiddleware({ accessToken, posSession })] }); // POS
const client = createApiClient({ middleware: [backOfficeMiddleware({ expectedTenant })] });     // BackOffice: expectedTenant = the tab's pinned company (FC §6)
```

Every non-2xx answer throws an `ApiError` (`status`, `code`, `title`, `detail`, `errors`, `acrValues`, `maxAge`); a network failure throws `ApiError {status: 0, code: 'NETWORK'}`. `readEventStream` reads the POS notification stream from a `fetch` body (call with `parseAs: 'stream'`).
