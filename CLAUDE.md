# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Status

The product specification is [docs/product_description.md](docs/product_description.md). It is written in Portuguese and is authoritative: a SaaS multi-tenant platform (shared schema with `tenant_id` and PostgreSQL RLS bound with `SET LOCAL`, Keycloak as the identity provider; amended 2026-11-15, see [docs/design/multitenancy-decisions.md](docs/design/multitenancy-decisions.md) and ADR 0040), a Spring Modulith modular monolith, PostgreSQL with Flyway, and React frontends for the POS and BackOffice. Read it before designing or implementing features.

restaurantPRO is at the scaffold stage. Only `backend/` exists: a freshly generated Spring Boot app with a single `BackendApplication` class and a context-load test. There is no domain code, persistence layer, or frontend in this directory yet.

## Shell commands: always through `rtk`

Every Bash command in this project runs through [RTK](https://github.com/rtk-ai/rtk), which filters command output to save tokens. The global rewrite hook (`rtk init -g`) is installed on this machine, but still write the `rtk` prefix explicitly in every command, including the commands inside subagents. The prefix keeps commands correct on machines without the hook, and the hook leaves commands that already start with `rtk` unchanged.

| Instead of | Use |
| --- | --- |
| `./mvnw <goals>` | `rtk mvn <goals>` (it detects and runs `./mvnw` itself) |
| `git <cmd>` | `rtk git <cmd>` |
| `pnpm install` / `pnpm typecheck` | `rtk pnpm install` / `rtk pnpm typecheck` |
| `pnpm test`, `pnpm --filter <app> test` | `rtk test pnpm test`, `rtk test pnpm --filter <app> test` |
| `pnpm lint` | `rtk lint` |
| Playwright (`pnpm exec playwright test`) | `rtk playwright test` |
| `docker`, `psql`, `gh`, `grep`, `find`, `ls`, `curl` | `rtk docker`, `rtk psql`, `rtk gh`, `rtk grep`, `rtk find`, `rtk ls`, `rtk curl` |
| Anything without its own filter | `rtk err <cmd>` (errors only) or `rtk summary <cmd>` |
| Long-running processes (dev servers) or when you need the full output | `rtk proxy <cmd>` (no filter) |

To check the equivalent of a command, run `rtk rewrite "<cmd>"`. If a filter hides information you need for debugging, run the command again with `rtk proxy`.

## Backend (`backend/`)

- Spring Boot 4.1.2 (parent POM), Java 25, Maven wrapper (Maven 3.10.0).
- Dependencies: `spring-boot-starter-webmvc` (servlet stack, not WebFlux), `spring-boot-devtools`, `spring-boot-starter-webmvc-test`.
- Base package: `com.xavier.backend`. Config: `src/main/resources/application.yaml`.

Run these from `backend/`:

```bash
rtk proxy ./mvnw spring-boot:run                            # run the app (port 8080 by default)
rtk mvn test                                                # run all tests
rtk mvn test -Dtest=BackendApplicationTests                 # run one test class
rtk mvn test -Dtest=BackendApplicationTests#contextLoads    # run one test method
rtk mvn package                                             # build the jar into target/
```

The empty `<license>`, `<developers>` and `<scm>` elements in `pom.xml` are deliberate. They block inheritance from the Spring Boot parent POM (see `HELP.md`), so leave them in place.

## Git

The enclosing git repository is rooted at `/Users/xavier` (the home directory), not at this project. `git status` therefore lists unrelated home-directory files. Scope git commands to this directory (for example `rtk git status .` or `rtk git add <paths>`), and never run `git add -A` or `git add .` from a parent directory.

`.github/modernize/java-upgrade/` holds tool-use recording hooks from a Java-upgrade tool. It is not CI.

## UI refactor (Google Stitch)

The frontends are being restyled to Google Stitch project **15612001253409631373**. The design system it implies, the token diff and the change set are in [docs/design/DESIGN.md](docs/design/DESIGN.md).

Rules:

- Use only Stitch project ID 15612001253409631373 (`projects/15612001253409631373` where a tool wants that form). Do not list or read other Stitch projects.
- Stitch output is a visual and structural spec, never source code. Do not paste Stitch HTML or CSS into the codebase.
- The refactor is presentation only. Preserve data fetching, state, handlers, routing, validation, i18n keys (pt-PT), analytics events and test IDs. Do not change API clients, stores or backend contracts.
- Reuse existing components before creating new ones. Use design tokens, not hard-coded colours, px values or arbitrary Tailwind values.
- Accessibility: semantic HTML, labelled controls, visible focus states and WCAG AA contrast.
- Each phase and each screen stops for the user's approval.

Stitch screen → route:

| Stitch screen | Screen ID | Route / files |
| --- | --- | --- |
| POS — Sala e Gestão de Mesas | `490260d04c6544c691d6d99fb5504725` | POS `/tables`: `routes/tables-page.tsx`, `features/dining/components/*`, `routes/status-bar.tsx`, `routes/pos-sections.tsx` (the section bar) |
| POS — Registo de Pedidos e Rondas | `0915c4b97df3450a9254db74db6ebf93` | POS `/orders/:orderId`: `routes/order-page.tsx`, `features/orders/components/*`, `features/catalog/components/*` |
| BackOffice — Lojas, Terminais & Organização | `80048f80f7ab42548ccb6abde024709c` | BackOffice `/organization/terminals`: `routes/terminals-page.tsx`, `features/organization/components/*`, `shell/*`; `/organization/stores`: `routes/stores-page.tsx`; `/organization/dining`: `routes/dining-page.tsx` (stub) |
| POS — Conta e Pagamento (Split) | `918d457984464ab8aab66c8a10b5dc04` | POS `/orders/:orderId/payment`: `routes/payment-page.tsx` (stub) |
| POS — Caixa e Fecho de Turno | `46816d9f62244326a86cc297374c57f2` | POS `/cash`: `routes/cash-page.tsx` (stub) |
| POS — Gestão de Reservas de Sala | `120ebed0d8ce45bdb2f41b862ca34536` | POS `/reservations`: `routes/reservations-page.tsx` (stub) |
| POS — Modal Nova Reserva (F2) | `b9dea7eb834f4ced8187cee7750abe36` | POS `/reservations` (a dialog of it): `routes/reservations-page.tsx` (stub) |
| POS — Modal Novo Pedido Takeaway & Delivery (F3) | `5f9e2ce74f7446638b80784c115639f9` | POS `/delivery` (a dialog of it): `routes/delivery-page.tsx` (stub) |
| POS — Despacho de Takeaway & Delivery | `89da103fa7cf4862bf796c0a322a0310` | POS `/delivery`: `routes/delivery-page.tsx` (stub) |
| POS — Guia de Transporte & Conferência (F8) | `2115493c9fef47659a64933b32a49005` | not built: outside the spec, no backend |
| KDS — Kitchen Display System & Controlo de Produção | `306764cb38734a80b69d62d00055a2c6` | not built: outside the spec, no backend (no KDS app) |
| BackOffice — Catálogo & Versões | `f1207b54e1394391bec6f36576f5db94` | BackOffice `/catalog`: `routes/catalog-page.tsx` (stub) |
| BackOffice — Produção, Encaminhamento & Impressoras | `0d63d74ec522441585ba71fae5c16a83` | BackOffice `/production`: `routes/production-page.tsx` (stub) |
| BackOffice — Stocks, Receitas & Compras | `cb5e3c5de1ab40929f2150dc1a8f91d3` | BackOffice `/inventory`: `routes/inventory-page.tsx` (stub) |
| BackOffice — Financeiro, Fechos & Auditoria | `45594d6f3d8a49c99a2628a8720811d2` | BackOffice `/finance`: `routes/finance-page.tsx` (stub) |
| BackOffice — Clientes, Crédito & Conta Corrente | `8937697b29084dc98845ed0a91e1351f` | BackOffice `/customers`: `routes/customers-page.tsx` (stub) |
| BackOffice — Utilizadores, Empregados & Permissões RBAC | `e1e8204a948a449b830addea0d42f0db` | BackOffice `/people`: `routes/people-page.tsx` (stub) |
| BackOffice — Zonas & Tarifários de Delivery | `836f7a7c24fd4403b7dd6a9907e2963a` | BackOffice `/delivery/zones`: `routes/delivery-zones-page.tsx` (stub) |
| BackOffice — Relatório & Conferência de Entregas e Guias AT | `88b0c854343d4a64817fcd880a7e755e` | BackOffice `/delivery/report`: `routes/delivery-report-page.tsx` (stub; the transport guides are not built: outside the spec, no backend) |

A "stub" page renders the section or area header and "Em preparação."; its slice replaces only that page file and its own feature folder. A feature's own error texts go in `errorMessage(error, { messages })`, never in `lib/error-messages.ts`.

Routes with no Stitch screen take the tokens only: POS `/enrol`, `/login` and `/tables/:tableId` (a redirect), and BackOffice `/`, `/step-up/resume` and not-found.

## Agent skills

### Issue tracker

Local Markdown: issues under `docs/issues/<feature>/`, specs under `docs/prd/`; external PRs are not a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`) as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` (glossary) and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
