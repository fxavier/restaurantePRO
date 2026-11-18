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

The first Playwright run on a machine needs the browser: `rtk proxy pnpm exec playwright install chromium`.

## Ports and proxies

| app | dev | preview (Playwright) | `vite dev` forwards |
|---|---|---|---|
| POS | 5173 | 4173 | `/api/v1/pos`, `/api/v1/auth` → backend `http://localhost:8080` (`BACKEND_URL`) |
| BackOffice | 5174 | 4174 | `/api`, `/bff`, `/oauth2`, `/login` → BFF `https://localhost:8443` (`BFF_URL`), Host kept |

Each app is same-origin with its API (ADR 0041), so the backend needs no CORS. The dev ports are fixed: the dev realm and `backend/run-local.sh` name them. `E2E_POS_URL` and `E2E_BACKOFFICE_URL` point Playwright at already-running origins instead of starting the preview servers.

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
const client = createApiClient({ middleware: [backOfficeMiddleware()] });                     // BackOffice
```

Every non-2xx answer throws an `ApiError` (`status`, `code`, `title`, `detail`, `errors`, `acrValues`, `maxAge`); a network failure throws `ApiError {status: 0, code: 'NETWORK'}`. `readEventStream` reads the POS notification stream from a `fetch` body (call with `parseAs: 'stream'`).
