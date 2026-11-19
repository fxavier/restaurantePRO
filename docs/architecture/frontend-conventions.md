# Frontend conventions (POS and BackOffice)

Status: **APPROVED** 2026-11-18 by Xavier Francisco ("aprovado", frontend R1 Phase 2 gate). Owner: `software-architect`. Decided with the user in the frontend kickoff alignment (rounds R1–R5, 2026-11-18). Sources: [product_description.md](../product_description.md) (§n), [multitenancy-decisions.md](../design/multitenancy-decisions.md) (D182, D184, D186), ADRs [0006](../adr/0006-idempotency-key.md), [0013](../adr/0013-pos-context-from-session.md), [0015](../adr/0015-idempotency-r2-rules.md), [0018](../adr/0018-idempotent-multi-step-commands.md), [0040](../adr/0040-saas-multitenancy-tenant-id-rls-keycloak.md), [0041](../adr/0041-frontend-origins-and-pos-delivery.md), [0042](../adr/0042-typed-api-client-from-openapi-snapshot.md), [0043](../adr/0043-frontend-ci-and-real-stack-e2e.md), [bff/README.md](../../bff/README.md). Design tokens: [frontend-design-system.md](../design/frontend-design-system.md).

These conventions win over `.claude/agents/frontend-developer.md` where they differ (that file says so). Two rules of that file are amended here: authentication (§6, the POS uses no cookies) and the POS touch target (§12, 48 px, not 44 px).

## 1. Stack (fixed by the agent files, restated)

Vite, React, TypeScript `strict`, React Router; Tailwind with shadcn/ui; TanStack Query; React Hook Form with Zod; Vitest, Testing Library, MSW, Playwright; pnpm workspace. No axios, no second state manager, no UI kit besides shadcn, no date library (`Intl` and `Date`).

Added dependencies, one sentence each:

| package | where | why |
|---|---|---|
| `openapi-typescript` (dev) | `packages/api-client` | generates the API types from the committed OpenAPI snapshot (ADR 0042) |
| `openapi-fetch` | `packages/api-client` | a typed `fetch` wrapper with middleware; replaces hand-written request code without overlapping TanStack Query |
| `@fontsource-variable/inter` | `packages/ui` | self-hosted Inter, so kiosks make no third-party font call and prices use tabular numerals |
| `lucide-react` | `packages/ui` | shadcn's icon set; one icon library |
| `sonner` | `packages/ui` | shadcn's toast component |

Added later, by the issue that first needs it: `@tanstack/react-table` (first list needing column sort or filter UI), shadcn charts / `recharts` (first report). No Storybook. No i18n library.

## 2. Workspace layout

```text
frontend/
├── package.json             # scripts: lint, typecheck, test, build, e2e; "packageManager" pins pnpm
├── pnpm-workspace.yaml
├── apps/
│   ├── pos/                 # touch-first FrontOffice, served on its own origin (ADR 0041)
│   └── backoffice/          # management interface, served on the BFF's origin
├── packages/
│   ├── ui/                  # shadcn components, tokens.css, Inter, shared primitives
│   └── api-client/          # openapi.json snapshot, generated schema.d.ts, client factory + middleware, SSE reader
└── e2e/                     # Playwright, against the real stack (ADR 0043)
```

Inside an app, code is organised by feature, mirroring the backend modules (`features/dining`, `features/orders`, `features/organization`…), each with `components/`, `hooks/`, `schemas.ts` and tests beside the code. `routes/` holds thin page components; `lib/` holds app setup (query client, router, auth, SSE). A folder exists only when it has content. Code moves to `packages/ui` only when both apps use it. A feature never imports another feature's internals; shared pieces go to `lib/` or a package.

## 3. Origins and serving (ADR 0041)

| app | production origin | `/api` goes to | dev (Vite proxy) |
|---|---|---|---|
| BackOffice | `backoffice.<domain>` (static SPA + BFF on one origin) | the BFF (`/api/v1/backoffice/**`, `/api/v1/auth/**`, `/bff/**`, `/oauth2/**`, `/login/**`) | proxy to the BFF |
| POS | `pos.<domain>` (static SPA + reverse proxy) | the backend (`/api/v1/pos/**`, `/api/v1/auth/**`) | proxy to the backend |

Each app is same-origin with its API, so the backend needs no CORS. Both apps call `/api/v1/auth/**`, which is why they need separate origins: on the BackOffice origin that path is the BFF's relay; on the POS origin it is the backend. The POS also calls the devices realm's token endpoint on Keycloak's origin, which needs the POS origin in each `pos-*` client's web origins (backend gap G13).

Environment-specific values (Keycloak devices issuer, POS origin) are Vite build-time variables (`VITE_*`), one build per environment.

## 4. Routing

React Router, data routers not required. Routes are flat and few.

- POS: `/enrol` (no device key yet) → `/login` (PIN pad) → `/tables` (home) → `/orders/:orderId`. A guard sends every route to `/enrol` without a device key and to `/login` without a POS session.
- BackOffice: `/` (overview), one route per §5 area as it arrives (R1: `/organization/terminals`), `/step-up/resume`. The store selection is the `?storeId` search parameter on every store-scoped route; the tenant selection lives in the BFF session.

## 5. State

- Server state: TanStack Query only. Query keys start with the API path segments (`['pos', 'tables']`, `['pos', 'orders', id]`), so an SSE topic maps to a key prefix. Defaults: `staleTime` 0 on POS, 30 s on BackOffice; queries retry twice on network errors; **mutations never retry** (§8).
- Local UI state: `useState`/`useReducer`. No global store. The POS device identity (client id, key handle) lives in IndexedDB; the access token and POS session token live in memory only (a module-level variable in `apps/pos/src/lib/auth`), never in `localStorage` or `sessionStorage`.
- The server is the source of truth: totals, taxes, discounts, states and balances are shown as returned. A draft order line is a server row (`LineStatus.DRAFT`), not client state.

## 6. Authentication

### POS (D182; ADR 0040, 0013)

1. **Enrolment** (`/enrol`): the user types the one-time code; the app generates a P-256 key with `crypto.subtle.generateKey({name: 'ECDSA', namedCurve: 'P-256'}, false, ['sign'])` (non-extractable), stores the `CryptoKey` in IndexedDB, and sends `POST /api/v1/pos/enrolment {code, publicJwk}`. It stores the answered `clientId` and `storeId` with the key. Clearing site data un-enrols; the admin issues a new code.
2. **Terminal token**: `client_credentials` at the devices realm token endpoint with an RFC 7523 assertion (ES256; `iss` = `sub` = client id, `aud` = the devices realm's **issuer**, as the bridge's `AccessTokens` and the backend's `RealmAdmin` send it, proven against Keycloak 26; random `jti`, `exp` = now + 60 s), signed in the browser. Renewed when less than 60 s remain, and once on a 401 that is not `POS_SESSION_INVALID`. Keycloak unreachable (network, 5xx) → the "sem ligação" state. Token refused (`invalid_client`, `unauthorized_client`) → the full-screen "Terminal revogado ou inválido" state; the device key is never erased automatically (a skewed clock or a Keycloak misconfiguration would otherwise un-enrol every terminal), only by the confirmed "Registar de novo", which then opens `/enrol`. *(Amended 2026-11-18, issue 08.)*
3. **Employee**: PIN pad → `POST /api/v1/auth/pos-login {employeeCode, pin}` with the terminal bearer. `SessionView.posSession` is kept in memory. If `mustChangePin`, the PIN-change screen (`POST /api/v1/auth/pos-pin`) comes first. Every POS call sends `Authorization: Bearer <terminal JWT>` and `X-Pos-Session`.
4. **Idle and switching**: after 90 s without input (a constant), or on "Trocar empregado", the app calls `POST /api/v1/auth/pos-logout` and returns to the PIN pad. Nothing is lost: draft lines are server rows on the order. The server's 30-minute idle / 10-hour absolute expiry is the backstop; a 401 `POS_SESSION_INVALID` also returns to the PIN pad.
5. A PIN is never logged, stored, or put in a URL, a query key or an error report.

### BackOffice (D184; bff/README)

- Login: top-level navigation to `/oauth2/authorization/keycloak`. MFA is Keycloak's.
- The browser holds only `__Host-bff-session` (HttpOnly) and `__Host-XSRF-TOKEN`. Every `POST`/`PUT`/`PATCH`/`DELETE` sends `X-XSRF-TOKEN` with the cookie's value; every request sends `credentials: 'include'`.
- 401 `BFF_SESSION_EXPIRED` → navigate to the login (the BFF never redirects an API call).
- Current user: `GET /api/v1/auth/session` (`SessionView`: name, `permissions[]`). Navigation and controls are gated on `permissions` as a convenience; the backend enforces.
- Tenant: `GET /api/v1/auth/tenants`; the switcher appears only with more than one membership and calls `PUT /bff/tenant`, then reloads the page on the same area (without `?storeId`).
- Tenant guard (FE-F16): the BFF session (the user who last logged in) and its company selection are shared by all tabs. Each tab pins, in memory, `{tenantId, userId}` from its first successful session read and never moves it; only a page load starts a new pin. Once pinned, **every** call sends `X-Expected-Tenant: <pinned tenantId>` (`backOfficeMiddleware({ expectedTenant })`); before that, none. The call throws `TENANT_CHANGED` instead of handing over an answer when: the BFF refused a write (409, the selection differs); the answer's `X-Relayed-Tenant` differs from the pin; the backend refused the expected tenant (403 `TENANT_FORBIDDEN`: another user, without that membership, logged in from another tab); or a session read names another `tenantId` or `userId`, stamped or not. On `TENANT_CHANGED` the tab sets a one-shot sessionStorage flag (a boolean: was a write refused) and reloads at `/`; the reloaded page shows "A sessão ou a empresa ativa mudou noutro separador. Empresa ativa: <tenantName>.", followed by "A última alteração não foi guardada." when a write (a mutation) was refused. The tab's own switch is `PUT /bff/tenant`, then at once a page load on the same area, which nothing read meanwhile can turn into a notice. The client never reconciles loaded data across a change: it reloads.
- **Step-up** (401 `MFA_STEP_UP_REQUIRED`, with `acrValues`, `maxAge`): a dialog "Confirme a sua identidade" explains the hop; on confirm, the pending command `{method, path, body, idempotencyKey, returnTo}` is written to `sessionStorage` (never a secret: commands carrying a PIN or a secret are not resumable and are re-entered), and the browser navigates to `/bff/step-up?acr_values=…&max_age=…&return=/step-up/resume`. `/step-up/resume` restores the form and shows "Continuar", which repeats the request with the same `Idempotency-Key`, then removes the stored command.
- Logout: `POST /bff/logout` with the CSRF header, as a form submission (the answer is a redirect).

## 7. API client (ADR 0042)

- `packages/api-client/openapi.json` is a committed snapshot of the backend contract; `pnpm --filter api-client generate` turns it into `schema.d.ts`. CI fails when the snapshot and the backend's export differ (backend gap G1 provides the export).
- `createApiClient({ baseUrl, middleware })` wraps `openapi-fetch`. Middleware per app: POS adds `Authorization` and `X-Pos-Session`; BackOffice adds `X-XSRF-TOKEN` on unsafe methods and `credentials: 'include'`. Both parse problem+json into one `ApiError {status, code, title, detail, errors?, acrValues?, maxAge?}` and map a network failure to `ApiError {status: 0, code: 'NETWORK'}`.
- Features call the client only through their own hooks (`useTables`, `useConfirmBatch`); components never call `fetch` or the client.

## 8. Idempotency and retries (ADR 0006, 0015, 0018)

- Every endpoint whose contract requires `Idempotency-Key` gets one per **user intent**: created with `crypto.randomUUID()` when the user presses the button (each tap on a product is one intent), held with the pending mutation, reused for every retry of that intent, discarded on success or on a definitive business error (4xx other than 409).
- The action's button is disabled while its request is in flight.
- Mutations never retry automatically. A network error, 502, 503 or 504 shows "Sem ligação" with "Tentar novamente", which resends with the **same key**. 409 "in progress" (a concurrent duplicate) waits 1 s and resends the same key, up to three times. 422 for a reused key is a client bug: logged without the body and shown as a generic error.
- The client sends a key exactly where the generated contract marks the header required, and nowhere else.
- Every request has a timeout (API 15 s, terminal token 10 s; the whole answer; the SSE stream only until it starts); a timeout is an unknown outcome (status 0 NETWORK), retried with the same key.

## 9. Errors

| answer | UI |
|---|---|
| 400 with `errors[]` | `setError` on the matching React Hook Form fields; the rest as a form-level message |
| 401 `POS_SESSION_INVALID` | back to the PIN pad |
| 401 `BFF_SESSION_EXPIRED` | to the login |
| 401 `MFA_STEP_UP_REQUIRED` | step-up dialog (§6) |
| 403 | "Sem permissão para esta ação" inline; `TENANT_SUSPENDED`/`TENANT_CLOSED` as a full-page state |
| 404 | the resource's "não encontrado" state |
| 409 (version conflict, `PRICES_CHANGED`, `CATALOG_CHANGED`) | refetch, show what changed, let the user retry |
| 429 | "Demasiadas tentativas" with `Retry-After` when present |
| 0 / 502 / 503 / 504 (`BACKEND_UNAVAILABLE`, `IDP_UNAVAILABLE`) | "Sem ligação" banner; confirmations disabled |
| other 5xx | toast "Erro inesperado (código)"; nothing is assumed to have happened |

Messages are Portuguese and live in the app: `apps/<app>/src/lib/error-messages.ts` maps known `code`s to text; unknown codes show `title`. `packages/api-client` stays language-neutral.

## 10. Live updates (§9, D60)

- POS only in R1: `GET /api/v1/pos/notifications` (`text/event-stream`, event `change`, data `{topic, id}`). Native `EventSource` cannot send `Authorization` or `X-Pos-Session`, so `packages/api-client` has a small `fetch` + `ReadableStream` SSE reader (no library).
- A message only invalidates queries: `TABLES` → `['pos','tables']`; `ORDER` → `['pos','orders', id]` and `['pos','tables']`; `CATALOG` → `['pos','catalog']`; `PRINT_JOBS`, `PAYMENT_ATTEMPT` → their keys. The payload is never applied.
- On reconnect, invalidate every `['pos']` query. While the stream is down, poll the visible screen's queries every 15 s. Reconnect with backoff 1, 2, 5, 10, 15 s.
- The BackOffice has no stream in R1 (gap G9); its lists refetch on focus.

## 11. Offline and availability (§11, ADR 0040)

No service worker, no offline queue, no optimistic update on a financial or production command. When the backend, the BFF or Keycloak is unreachable the app shows "Sem ligação — nenhuma operação é confirmada" and disables every confirming button. Optimistic UI is allowed only for local, unsent input (the PIN pad, a search box).

## 12. Money, time, locale, copy

- One locale constant per app: `pt-MZ`. Copy is European Portuguese, written in the components, using the glossary's PT terms ([CONTEXT.md](../../CONTEXT.md)).
- Money is the contract's `Money {amount, currency}` (amount a JSON number or string, as the endpoint defines). Display with `Intl.NumberFormat('pt-MZ', {style: 'currency', currency})`, the currency always from the API. The client does no money arithmetic, full stop. A draft's price shown before confirmation is the server's preview (`LineView.preview`) and the drafts' total is the server's `OrderView.draftGross`, shown as provisional; "Pedir" sends that `draftGross` amount back unchanged as `expectedGross`, the concurrency guard (D104, FE-F27). Amended 2026-11-19: the former exception (an exact client-side sum for `expectedGross`) is gone.
- Instants are ISO-8601 UTC; display in the store's timezone (from the store) with `Intl.DateTimeFormat`. A business day (`LocalDate`) is displayed as given, never derived from the clock.

## 13. Accessibility (WCAG 2.2 AA)

Semantic HTML and labelled controls; visible focus (2 px ring, `--ring`); contrast ≥ 4.5:1 for text and ≥ 3:1 for UI boundaries; status never by colour alone (label or icon); target size ≥ 24 px everywhere (2.5.8) and the POS sizes of the design system; BackOffice fully keyboard operable, dialogs trap and return focus; no hover-only affordance on the POS. Playwright runs `@axe-core/playwright` on each R1 screen (dev dependency of `e2e/`: automated a11y checks on every screen).

## 14. Testing

| what | tool | where |
|---|---|---|
| pure helpers, Zod schemas, middleware, SSE parser | Vitest | beside the code |
| components and feature flows: loading, empty, error, unavailable, 409, permission-hidden | Vitest + Testing Library + MSW | beside the code |
| §8.5 flows and §14 criteria | Playwright against the real stack (ADR 0043) | `frontend/e2e/` |

Test-first (`tdd`). Query by role, label or text. MSW handlers are typed from `schema.d.ts`; a handler for an endpoint the backend lacks is named `gap-<id>` and listed in the issue. Playwright: viewports 1024×768, 1366×768, 1920×1080 for the POS, 1280×800 and 1024×768 for the BackOffice; screenshots of each R1 screen are attached to the PR.

## 15. Scripts and CI (ADR 0043)

From `frontend/`: `rtk pnpm install`, `rtk lint`, `rtk pnpm typecheck`, `rtk test pnpm test`, `rtk pnpm build`, `rtk playwright test`, `rtk err pnpm --filter api-client generate`. Each package defines the same script names. GitHub Actions runs lint, typecheck, test and build per app on every PR touching `frontend/`, and the e2e job against the compose stack.

## 16. Contract and environment gaps

Found while aligning (2026-11-18). Each one that R1 needs becomes its own backend or deploy issue in `docs/issues/frontend-r1/`; the rest are recorded for the release that needs them. MSW stands in until a gap closes; the backend's shape is never invented silently.

| id | gap | needed by |
|---|---|---|
| G1 | No OpenAPI export without a running server, and no committed snapshot (ADR 0042) | R1 scaffold |
| G2 | `TableMap` has no position, shape or size, so the map is a grid by area | later (drawn floor plan) |
| G3 | No product images or upload | later |
| G4 | No priced modifiers; only variants, menu component groups and a free-text note | later (product decision) |
| G5 | No kitchen display states (prepared, ready, bump) and no production-ticket SSE topic | later (KDS) |
| G6 | No permission catalog with labels (role editor) | later (BackOffice access) |
| G7 | No lock / switch-employee endpoint; R1 uses logout and login (ADR 0041) | accepted for R1 |
| G8 | No active store in the BFF session; R1 keeps `?storeId` in the URL | accepted for R1 |
| G9 | No BackOffice live-update stream | later |
| G10 | No receipt PDF, email or SMS | later |
| G11 | D182 documents `POST /pos/sessions`; the code is `POST /api/v1/auth/pos-login` (doc fix) | fixed in the planning PR |
| G12 | `frontend-developer.md` says every app uses cookies and CSRF, and a 44 px POS target; this document amends both (agent-file fix) | fixed in the planning PR |
| G13 | `pos-*` clients are created without web origins, so the browser cannot call the devices realm's token endpoint | R1 POS |
| G14 | `deploy/` serves no frontend: static hosting and the per-app reverse-proxy rules of ADR 0041 | R1 e2e and deploy |
| G15 | The dev realm's `backoffice-bff` redirect URIs do not include the BackOffice dev origin | R1 BackOffice |
| G16 | No seeded BackOffice test user with a known TOTP secret for the e2e (dev and test realms only) | R1 e2e |
