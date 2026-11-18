# PRD frontend R1: first POS and BackOffice slices

Status: **APPROVED** 2026-11-18 by Xavier Francisco ("aprovado", frontend R1 Phase 2 gate, together with the conventions, the design system and ADRs 0041–0043). Sources: [product_description.md](../product_description.md) (cited §n), [frontend-conventions.md](../architecture/frontend-conventions.md) (cited FC §n, gaps Gn), [frontend-design-system.md](../design/frontend-design-system.md) (DS), ADRs [0041](../adr/0041-frontend-origins-and-pos-delivery.md), [0042](../adr/0042-typed-api-client-from-openapi-snapshot.md), [0043](../adr/0043-frontend-ci-and-real-stack-e2e.md), [0040](../adr/0040-saas-multitenancy-tenant-id-rls-keycloak.md), [multitenancy-decisions.md](../design/multitenancy-decisions.md) (D182, D184), [CONTEXT.md](../../CONTEXT.md). Baseline: `main` at 70a688d7 (R6 merged); no frontend code exists.

## Problem Statement

restaurantPRO has a complete backend for service, catalog, cash, fiscal and stock, but nobody can use it: there is no screen. A waiter cannot open a table or send a round to the kitchen; an administrator cannot even put a terminal into service, because enrolling a POS terminal needs a one-time code issued from the BackOffice and a terminal able to make and keep its own key. The two login paths decided in R6 (a terminal that signs its own Keycloak assertion plus an employee PIN, and a BackOffice behind a BFF with mandatory MFA and step-up) have never been exercised by a real browser.

## Solution

Two thin vertical slices in one pnpm workspace with a shared UI library and a typed API client:

- **BackOffice:** the administrator signs in through Keycloak with MFA, chooses the company when they belong to several, picks a store, registers a POS terminal and issues its enrolment code; issuing the code asks for a fresh second factor (step-up) and then resumes the action without retyping it.
- **POS:** on a terminal in kiosk mode, the administrator types the enrolment code once; from then on a waiter logs in with code and PIN, sees the table map of the store, opens a free table with the number of guests, adds items (variants, menu choices, a note), sees the server's prices and total, and presses "Pedir" to confirm the round. A second terminal of the same store sees the table become occupied and the order change without reloading. The terminal returns to the PIN pad after 90 s idle or on "Trocar empregado"; it shows "Sem ligação" and confirms nothing whenever the service is unreachable.

## User Stories

### BackOffice: access

1. As a company administrator, I want to sign in with my Keycloak account and second factor, so that one strong login protects my company's configuration (§10, D184).
2. As a company administrator, I want my session to live only in a cookie the page's scripts cannot read, so that a script injection cannot steal my access (FC §6).
3. As a company administrator, I want to be sent back to the sign-in page when my session has expired, so that I never work on a page that silently fails.
4. As an accountant with several client companies, I want to choose which company I am working in from the top bar, so that I never act in the wrong company (D177).
5. As a user with a single company, I want no company switcher at all, so that the screen stays simple.
6. As a user who switches company, I want every list to reload for the new company, so that nothing from the previous company stays on screen.
7. As a company administrator, I want to choose the store from the top bar and keep it in the address, so that a reload or a shared link shows the same store.
8. As a user, I want the side menu to show only the areas I have permission for, so that I am not led to screens that refuse me (§3).
9. As a user, I want a refused action to say I lack permission, so that I know it is not a fault of the system (§10).
10. As a user, I want to sign out from the user menu, so that the shared computer is not left signed in.
11. As a user whose company is suspended or closed, I want a clear full-page message, so that I understand why nothing works.

### BackOffice: terminals and step-up

12. As a company administrator, I want to see the terminals of the selected store with their kind and state, so that I know which devices are in service (§5).
13. As a company administrator, I want to register a POS terminal for the selected store with a name, so that it can be enrolled.
14. As a company administrator, I want to issue a one-time enrolment code for a terminal and see it once, large and easy to copy, so that I can type it on the terminal (D182).
15. As a company administrator, I want to be told the code expires in 24 hours and can be used once, so that I issue it when I am at the terminal.
16. As a company administrator, I want to revoke a terminal, so that a lost or stolen device stops working at once.
17. As a company administrator, I want a sensitive action to ask me to confirm my identity when my second factor is older than five minutes, so that an unattended session cannot register or revoke devices (D184).
18. As a company administrator, I want to come back from the identity confirmation to the same form with my input kept and a "Continuar" button, so that I neither retype nor risk doing the action twice (FC §6).
19. As a company administrator, I want a field error from the server shown next to the field, so that I can correct it (§9).
20. As a company administrator, I want a "Sem ligação" message when the service cannot be reached, with a way to try again that cannot duplicate the action, so that I never wonder whether it happened (§9, FC §8).

### POS: terminal and employee

21. As a company administrator at a new terminal, I want to type the enrolment code once, so that the terminal is put into service without any shared password (D182).
22. As a company administrator, I want a wrong, used or expired code to be refused with a clear message, and repeated attempts to be slowed down, so that codes cannot be guessed.
23. As a waiter, I want to log in with my employee code and PIN on a large numeric pad, so that I start working in seconds (§4, ADR 0008).
24. As a waiter, I want my PIN digits hidden and never kept by the terminal, so that colleagues cannot learn it (§10).
25. As a waiter whose PIN was reset, I want to be asked for a new PIN before anything else, so that only I know it.
26. As a waiter, I want a wrong PIN to be refused without saying which part was wrong, and to know that repeated failures lock me until a manager unlocks me, so that guessing is pointless.
27. As a waiter, I want the terminal to return to the PIN pad after 90 seconds without use, so that nobody acts in my name when I walk away.
28. As a waiter, I want a "Trocar empregado" button, so that a colleague can log in without waiting.
29. As a waiter, I want unsent lines to remain on the order after a logout, so that a colleague or I can continue it.
30. As a manager, I want a terminal whose enrolment was revoked to stop working on its next request, so that a stolen device is useless.

### POS: table map

31. As a waiter, I want the tables of my store grouped by area, so that I find a table the way the room is organised (§4).
32. As a waiter, I want each table to show whether it is free or occupied by fill and label, with guests and time open, so that I see the room at a glance (§4, DS).
33. As a waiter, I want locked, subtotal-printed and reserved tables marked with an icon and a label, so that I never rely on colour alone (§8.4).
34. As a waiter, I want a table opened or changed on another terminal to update on mine without reloading, so that two of us never open the same table (§9, §14).
35. As a waiter, I want the map to keep working by periodic refresh when live updates drop, so that I am never shown a stale room for long (§9).

### POS: order and round

36. As a waiter, I want to open a free table by entering the number of guests on a numeric pad, so that the order starts in two taps.
37. As a waiter, I want to open an occupied table and land in its order, so that I add the next round.
38. As a waiter, I want to be refused, with the reason, when a table is locked or was just taken by someone else, so that I understand what happened.
39. As a waiter, I want to browse products by family and find them by search or code, so that I add items fast (§4).
40. As a waiter, I want to choose a variant and a menu's choices when an item has them, so that the kitchen gets exactly what was asked.
41. As a waiter, I want to add a preparation note to a line, so that special requests reach the kitchen (§4).
42. As a waiter, I want to change the quantity of or remove a line that has not been sent, so that I correct mistakes before the kitchen sees them.
43. As a waiter, I want unsent and sent lines shown apart, sent lines grouped by round, so that I know what the kitchen has (§4, §8.3).
44. As a waiter, I want the prices, taxes and total shown as the server computes them, so that what I tell the customer matches the bill (§8.4).
45. As a waiter, I want one large "Pedir" button that confirms the round once even if I tap twice or the network stutters, so that the kitchen never gets a round twice (§7, §9, §14).
46. As a waiter, I want to be told when prices or the catalog changed before my round was confirmed, see the new values and confirm again, so that the customer pays the current price (§8.4).
47. As a waiter, I want to be told when the order was changed by someone else, see the current order and retry, so that no line is lost (§14).
48. As a waiter, I want a clear "Sem ligação" state that disables "Pedir" when the service is unreachable, so that I never believe a round was sent when it was not (§11, §14).
49. As a waiter, I want an action whose outcome is unknown after a lost connection to be retried safely with "Tentar novamente", so that I can recover without duplicating it.

### Cross-cutting

50. As any user, I want every screen to show loading, empty, error and unavailable states, so that I always know what the system is doing.
51. As any user, I want the text in European Portuguese with amounts and dates in Mozambican format and the company's currency, so that the screens read naturally (FC §12).
52. As a keyboard user of the BackOffice, I want every control reachable and operable by keyboard with visible focus, so that I can work without a mouse (WCAG 2.2 AA).
53. As a developer, I want a type error when the backend contract changes, so that the frontend never drifts silently from the API (ADR 0042).
54. As a maintainer, I want every pull request touching the frontend checked by lint, type check, tests, build and the end-to-end flows, so that a broken flow cannot merge (ADR 0043).

## Implementation Decisions

- **Workspace (FC §2):** one pnpm workspace with the two apps (POS, BackOffice), a UI package (shadcn components, tokens, Inter, shared primitives `ConnectionBanner`, `StatusBadge`, `Money`, `DataState`, `ConfirmButton`) and an API-client package. Apps are organised by feature mirroring backend modules: POS `organization` (enrolment), `identity` (PIN), `dining` (map), `orders` (order and round), `catalog` (product browsing); BackOffice `identity` (session, tenant), `organization` (stores, terminals).
- **API client (ADR 0042):** types generated from a committed OpenAPI snapshot; a runtime client factory with per-app middleware; one `ApiError {status, code, title, detail, errors?, acrValues?, maxAge?}` with `status 0 / code NETWORK` for a network failure; a `fetch`-based SSE reader, because `EventSource` cannot send headers.
- **Origins (ADR 0041):** POS and BackOffice on separate origins, each same-origin with its API; no backend CORS; Vite proxies in development.
- **POS device and session (D182, FC §6):** non-extractable WebCrypto P-256 key and client id in IndexedDB; ES256 RFC 7523 assertion signed in the browser; terminal token and POS session token in memory only; renewal under 60 s left; every POS call carries the bearer and `X-Pos-Session`; idle 90 s or "Trocar empregado" → logout → PIN pad.
- **BackOffice session (D184, FC §6):** BFF cookies only; CSRF header on unsafe methods; current user and permissions from the session endpoint; company list from the tenants endpoint and selection through the BFF; store selection in the URL; step-up dialog with the pending command kept in session storage and resumed by "Continuar" with the same idempotency key.
- **Backend contracts used (all exist):** POS enrolment; POS login, PIN change, logout, session; tenants list; stores list; terminals list, create, enrolment code, revoke; POS catalog; table map; open table; order read; add, change and remove line; confirm batch; POS change notifications. Request and response shapes come from the generated types, never from hand-written guesses.
- **Idempotency (FC §8):** one key per user intent (each product tap, each "Pedir", each terminal registration), reused on every retry of that intent; mutations never retry automatically; 409 "in progress" retried with the same key up to three times.
- **Live updates (FC §10):** an SSE message only invalidates queries (`TABLES`, `ORDER`, `CATALOG`); reconnect invalidates every POS query; polling every 15 s while the stream is down.
- **Errors (FC §9)** mapped to inline field errors, full-page states, the "Sem ligação" banner or a toast; Portuguese messages per app keyed by the error `code`.
- **Design (DS):** tokens and densities as specified; POS landscape ≥ 1024 × 768 with 48 px targets and 56 px primary actions; BackOffice desktop, usable at 1024 px, keyboard operable.
- **Backend and environment prerequisites, each its own issue:** G1 (OpenAPI export without a running server), G11 (D182 route name), G12 (agent-file amendments), G13 (web origins on `pos-*` clients), G14 (frontend hosting and reverse-proxy rules in deploy), G15 (BackOffice dev origin as a redirect URI in the dev realm), G16 (e2e user with a known OTP secret, dev and test realms only).

## Testing Decisions

- **A good test** exercises behaviour a user or the API can observe: what is on screen, what request was sent (path, headers, body, idempotency key), what happens on each answer. It queries by role, label or text, never by class or component internals. Tests are written first and fail for the expected reason (`tdd`).
- **Seam 1 (primary): each app rendered whole** with its router, query client and the real API client, in Vitest + Testing Library, with MSW at the network edge, handlers typed from the generated schema. Every story's loading, empty, error, unavailable, 409, permission and step-up states are tested here. Handlers for an endpoint the backend lacks are named after their gap.
- **Seam 2 (top): Playwright against the real stack** (ADR 0043): PostgreSQL, Keycloak with the dev realms, backend, BFF, both apps behind their origins; data seeded through the public API and the provisioners. Two flows: (a) BackOffice sign-in with OTP → company and store → register terminal → issue code with step-up and resume; (b) POS enrol with that code → PIN login → map → open table → add items with a variant, a menu choice and a note → "Pedir", observed by a second browser context as a second terminal of the same store. Axe checks on every screen; screenshots at the DS viewports.
- **Unit tests** only for pure helpers: the SSE parser, the assertion builder, problem+json mapping, money and date formatting.
- **Prior art:** none in the frontend; backend parity in `R6AcceptanceTest` (tenant isolation over HTTP), `PosTerminalAuthTest` (terminal plus session headers) and the idempotency replay tests, whose behaviours the client relies on.

### Acceptance criteria (traceable)

| # | criterion | source |
|---|---|---|
| AC1 | An administrator with OTP signs in, and with two memberships switches company; lists reload for the new one | §10, D177, D184 |
| AC2 | Issuing an enrolment code with a stale second factor goes through step-up and completes once with the same idempotency key | D184, ADR 0006, FC §6 |
| AC3 | A terminal enrolled with the code obtains a token by a browser-signed assertion; the private key is non-extractable | D182, §10 |
| AC4 | Waiter PIN login works; a reset PIN forces a change first; 90 s idle returns to the PIN pad | ADR 0008, §10 |
| AC5 | Opening a free table and confirming a round of three lines (variant, menu choice, note) creates exactly one batch even with a double tap or a retried request | §7, §9, §14 #2 |
| AC6 | A second terminal sees the table become occupied and the new round without reloading | §9, §14 #2 |
| AC7 | Prices or catalog changed before confirmation → the waiter sees the new values and confirms again | §8.4, §14 #7 |
| AC8 | Backend or Keycloak unreachable → "Sem ligação", "Pedir" disabled, nothing shown as sent | §11, §14 #6 |
| AC9 | A revoked terminal is refused on its next request and returns to enrolment | D182, §10 |
| AC10 | A user without the permission does not see the terminals area, and the API refusal is shown if reached | §3, §10, §14 #9 |
| AC11 | Every R1 screen passes axe with no serious or critical violation; table states carry labels | WCAG 2.2 AA, §8.4 |
| AC12 | CI fails on lint, type, test, build or e2e failure, and on OpenAPI snapshot drift | ADR 0042, 0043 |

## Out of Scope

Payment, partitions, documents, closing an order, cash sessions (R2); takeaway, counter, pub, delivery and own-consumption modes (ADR 0033); voids, discounts, transfers, table moves and manager approvals in the POS; subtotal printing and print-job screens; reservations; a drawn floor plan (G2), product images (G3), priced modifiers (G4), kitchen display (G5); BackOffice catalog, users, roles, employees, stock, reports and every §5 area other than terminals; the platform console; BackOffice live updates (G9); dark mode; portrait and phone layouts; a PWA, service worker or offline queue; a native wrapper; English or any i18n library; Storybook; backend CI.

## Further Notes

- Visual gate: after the first POS screen and the first BackOffice screen, screenshots are shown to the user for approval before any further screen is built.
- The enrolment code shown once is a secret: it is never logged, never put in a query key, and never kept in the step-up resume store (a command whose response is a secret is resumed by repeating the request, whose body holds no secret).
- Gaps G2–G10 are recorded in FC §16 for the releases that need them; they are not R1 issues.
