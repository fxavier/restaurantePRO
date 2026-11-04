# restaurantPRO backend — decisions for approval

Status: **ACCEPTED** by Xavier Francisco, 2026-11-04. Approval also covers G2 (PRD) and G3 (slices) for R1. Blockers B1–B3 resolved (own git repo, Docker running, local Markdown PRD/issues). P1: country stays **configurable** (no market hard-coded). This document is the ADR record for D1–D33; supersede a decision with a new ADR in `docs/adr/`.
Source of truth: [product_description.md](../product_description.md). Where the spec already decides something, this document does not reopen it. Each decision has an id, so you can answer "D7: use X instead".

---

## 0. Blockers (must be resolved before any code)

| id | blocker | proposal |
|---|---|---|
| B1 | `restaurantPRO/` is untracked inside the home-directory git repo (`~`, remote `fxavier/ERPSystem_v1_4`). Committing would put this project into the ERPSystem repo, and worktrees for parallel agents would not contain it. | `git init` inside `restaurantPRO/`, first commit of the current scaffold + docs on `main`, all work on `feat/backend-stage1`. Add `restaurantPRO/` to the home repo's ignore list is **your** call (I won't touch the home repo). |
| B2 | Docker daemon is not running. Spec §12 requires real PostgreSQL in tests (Testcontainers). | You start Docker Desktop. Without it no persistence test can go red/green. |
| B3 | No issue tracker configured (`docs/agents/` missing). | PRD and slice issues as local Markdown under `docs/prd/` and `docs/issues/` (no GitHub until a remote exists). |

---

## 1. Scope and phasing

The full spec is 14 modules and 4 delivery stages (§13). That is too big for one reviewable run (>20 slices), so I split it the way the spec does. **Each stage is a separate run with its own approval.**

| run | content | status |
|---|---|---|
| **R1 (this run)** | Foundation + Stage 1: shared kernel, security, audit, idempotency, arch tests, `organization`, `identity`, `dining` (config only), `catalog` + publication, Docker Compose + backup/restore | detailed below |
| R2 | Stage 2: `orders` (rounds, batches, voids, transfers, table occupation), `production` (print queue + ESC/POS worker), `cash`, `settlement` base (cash + card-terminal payments, close order), `fiscal` (series, numbering, generic adapter), SSE notifications | outlined only, detailed when R1 ships |
| R3 | Stage 3: split/partial, `customers` + credit, reconciliation, audit query, `reporting`, failure recovery | outlined only |
| R4 | Stage 4: `inventory` (purchases, recipes, counts), `promotions` + loyalty, reservations, delivery | outlined only |

**D1 — Modules are created only when their stage starts** (YAGNI). R1 creates `shared`, `audit`, `access`, `organization`, `identity`, `dining`, `catalog`. Their dependency rules for later modules are fixed now (§4) so R1 cannot paint us into a corner.

---

## 2. Open product decisions (spec §15) — defaults I'll use unless you say otherwise

| id | question | default for R1 | impact if wrong |
|---|---|---|---|
| P1 | Initial country | **Not hard-coded.** Country lives in `CompanyProfile`; R1 needs no fiscal behaviour. Please confirm the target country (Mozambique?) before R2, because R2's fiscal adapter depends on it. | R2 fiscal adapter |
| P2 | Currency | One base currency per installation, configured at bootstrap (e.g. `MZN`). Single-currency in v1 as spec §8.4 says. | low — config only |
| P3 | Tax | Tax categories and rates are configurable data (catalog). No country's rates are seeded. | none |
| P4 | Payment provider | R2: cash + "external card terminal, operator confirms" adapter behind a `PaymentProvider` port. Real integrated provider when you name one. | R2 |
| P5 | Printers | R2: ESC/POS over raw TCP (port 9100), the common denominator for kitchen printers. | R2 |
| P6 | Hosting | Local server per company (spec recommendation). Compose stack in R1 targets that. | low |

---

## 3. Technical stack

| id | decision | why / alternative rejected |
|---|---|---|
| D2 | **Spring Boot 4.1.2, Java 25, Maven** — as scaffolded. | Already chosen. |
| D3 | **Spring Data JPA (Hibernate 7)** for persistence. | Spec talks about JPA entities (§8.2). jOOQ rejected: second paradigm, no gain at this size. |
| D4 | **PostgreSQL (one pinned major, e.g. `postgres:18`)**, same image in Compose and Testcontainers. | Spec §6. Pinned so tests = production. |
| D5 | **Flyway**, one history table, migrations in `db/migration/<module>/`. **Version blocks per module** so parallel agents never collide: `shared/audit 1xxx`, `organization 2xxx`, `identity 3xxx`, `dining 4xxx`, `catalog 5xxx`, later modules 6xxx+. File name `V2001__organization_create_store.sql`. | Migration numbering is a parallel hotspot; disjoint blocks make slices independent. Timestamps rejected: still collide in order on merge. |
| D6 | **One PostgreSQL schema per module** (`organization.store`, `catalog.item`, …). Cross-module foreign keys are **not** declared; integrity checked through the owning module's API (spec §8.2 "IDs e APIs públicas"). In-module FKs and constraints are mandatory. | Keeps modules extractable and boundaries visible in SQL. Alternative: single `public` schema — boundaries blur. |
| D7 | **IDs: UUIDv7** (`@UuidGenerator(style = VERSION_7)`), time-ordered for index locality. Human codes (store code, item code, terminal code) are separate unique columns. | Sequential bigint leaks volume and complicates merges/restores; v4 UUID fragments indexes. |
| D8 | **Spring Modulith 2.x** (core + JPA event publication registry + test). Module verify + `Documenter` output in `docs/architecture/modules/`. | Spec §6/§7. |
| D9 | **No Lombok, no MapStruct.** Records for DTOs/events, hand-written mapping. | Project developer conventions. |
| D10 | **springdoc-openapi** (Boot-4-compatible line) → `/v3/api-docs`, the source for the frontend's generated client later. | Spec §9 requires OpenAPI. |
| D11 | **Testing:** JUnit 5 + AssertJ for domain rules (no Spring); `@WebMvcTest` for HTTP shape/auth; `@ApplicationModuleTest` / `@DataJpaTest` with **one shared Testcontainers PostgreSQL** (`@ServiceConnection`) for constraints, migrations, concurrency, idempotency; `ApplicationModules.verify()` + ArchUnit for boundaries. Never H2. | Spec §12. |

New dependencies, all added once in slice S0 (the only slice that touches `pom.xml`): `data-jpa`, `flyway` (+ `flyway-database-postgresql`), `postgresql`, `validation`, `security`, `spring-session-jdbc`, `actuator`, `spring-modulith-starter-core/-jpa/-test`, `springdoc-openapi-starter-webmvc-ui`, `testcontainers-postgresql`, `archunit-junit5`. Versions from the Boot BOM wherever it covers them.

---

## 4. Module structure and boundaries

**D12 — Package layout** (project convention from `.claude/agents/developer.md`): `com.xavier.backend.<module>/{model, model/dto, model/event, repository, service, controller, exception}`. Only `model/dto`, `model/event` and an explicit public facade (`<Module>Api`, e.g. `OrganizationApi`) are exposed via `@NamedInterface`. Entities, repositories and controllers stay internal.

**D13 — Allowed dependencies** (declared in each `package-info.java`; violations fail the build):

```
shared        → (nothing)                     OPEN module: Money, ids, errors, idempotency, Clock
audit         → shared
access        → shared                          Permission, Actor, AccessPolicy (authorization primitives)
organization  → shared, audit, access
identity      → shared, audit, access, organization
dining        → shared, audit, access, organization
catalog       → shared, audit, access, organization
--- later runs ---
customers     → shared, audit, access, organization
promotions    → shared, audit, access, organization, catalog, customers
orders        → shared, audit, access, organization, dining, catalog, promotions     (NOT settlement)
production    → shared, audit, access, organization, orders::event                   (events only)
cash          → shared, audit, access, organization                                  (NOT orders)
fiscal        → shared, audit, access, organization, customers
settlement    → shared, audit, access, organization, orders, cash, fiscal, customers
inventory     → shared, audit, access, organization, catalog::event, orders::event, production::event
reporting     → events only from any module; nobody depends on reporting
```

Every module depends on `access` for one reason: it holds `AccessPolicy.require(Permission, storeId)`, the single server-side authorization check, plus `Permission` and `Actor`. `identity` (accounts, employees, roles, login) builds the `Actor` at login and depends on `organization` for stores and terminals; identity depends on no business module other than `organization`, so later modules may call its public API (e.g. `ApproverVerifier` from orders) without a cycle. *Amended 2026-11-04 (user decision): the original table put `AccessPolicy` in `identity`, which created an organization ↔ identity cycle.* Spec §6's direction rules (`orders ↛ settlement`, `cash ↛ orders`) are enforced by this table.

**D14 — `shared` is technical only:** `Money`, `Currency` handling, id helpers, `ProblemDetail` mapping and base exceptions, the idempotency mechanism, the clock bean, pagination DTO. No business rules.

---

## 5. Cross-cutting decisions

### Money and time
- **D15 — `Money`** = `BigDecimal` + `Currency`, scale = currency minor units (2 for MZN/EUR). Rounding **HALF_UP** at line level, once, in one place. Splitting an amount uses **largest-remainder** allocation so the parts always sum to the total (§8 "rateio dos cêntimos"). `double`/`float` banned by an ArchUnit rule.
- **D16 — Time:** an injected `Clock`. Instants are stored as `timestamptz`. Each store has a `ZoneId` and a **business-day cutoff** (default 05:00), so a business day can cross midnight (§4). The `CashDay` entity itself arrives in R2.

### Persistence rules
- **D17 — Optimistic locking** (`@Version`) on every aggregate edited by humans → **409** with `ProblemDetail`. Short pessimistic locks (`SELECT … FOR UPDATE`) only for fiscal numbering, payment and closing (R2).
- **D18 — Confirmed facts are immutable.** Corrections are new rows. Enforced in code (no setters) and, for `audit_event`, by a DB trigger that rejects `UPDATE`/`DELETE`.

### Events
- **D19 — Spring Modulith event publication registry (JPA)** for every cross-module event: published in the same transaction, persisted in `event_publication`, resubmitted on restart. Listeners use `@ApplicationModuleListener` and are idempotent through a unique key on the effect they produce. No Kafka (§7).

### Audit
- **D20 — Audit is synchronous, not event-driven.** `AuditLog.record(actor, approver, action, entityType, entityId, storeId, terminalId, reason, details)` writes `audit.audit_event` **in the caller's transaction**, so an audited change and its audit row commit or roll back together. Async events could lose the record on a crash. Query endpoints come in R3. Details never contain PINs, passwords or payment data (§10).

### API
- **D21 — Routes** exactly as in spec §9: `/api/v1/auth`, `/api/v1/pos/**`, `/api/v1/backoffice/**`. POS and BackOffice controllers call the **same** service methods.
- **D22 — Errors:** RFC 9457 `ProblemDetail` from one `@RestControllerAdvice`: 400 validation (with field errors), 401, 403, 404, **409 version/state conflict**, 422 business rule.
- **D23 — Pagination:** `?page=&size=&sort=`, response `{content, page, size, totalElements}`; max size 200.
- **D24 — Idempotency-Key** (shared mechanism, used from R1 by publish and other repeatable commands, heavily in R2): table `shared.idempotency_record(key, scope, request_hash, status, response_body, created_at)`, unique `(scope, key)`. Same key + same hash → stored response replayed. Same key + different hash → **422**. Concurrent duplicate → one wins via the unique constraint, the other gets 409 "in progress". Scope = operation name + actor.

### Security (spec §10)
- **D25 — Server-side sessions with Spring Session JDBC** (sessions survive restarts and can be listed or revoked per user, which spec §5 needs). Cookie `Secure; HttpOnly; SameSite=Lax`. CSRF enabled with a cookie-to-header token (SPA pattern). No JWT.
- **D26 — Two login flows, one identity model:**
  - BackOffice: `username + password`, BCrypt (an adaptive hash, built in). Strong-password rule. MFA (TOTP) is **deferred**; spec says "conforme implantação". It needs a decision later if remote admin access is planned.
  - POS: `employee code + PIN` **on a registered terminal**. A terminal is enrolled once by an admin (one-time enrollment code → long-lived device token stored hashed). The PIN is BCrypt-hashed. 5 failed attempts lock the employee until a manager unlocks it. The session carries `employeeId, terminalId, storeId`.
- **D27 — RBAC:** `Permission` is a **code-defined enum** (e.g. `CATALOG_PUBLISH`, `ORDER_VOID`, `CASH_CLOSE_APPROVE`), because permissions are tied to code paths. `Role` is data (a named set of permissions), seeded with the 7 profiles from §3 and editable. `EmployeeStoreAssignment(employee, store, role)` gives per-store scope, and an employee can have many. Every service use case calls `AccessPolicy.require(permission, storeId)`. Controllers do not decide authorization. **POS sessions are scoped to the terminal's store** (user decision 2026-11-04): a PIN login on a terminal grants only the store-scoped authorities of that store; BackOffice sessions carry all assigned stores.
- **D28 — Supervisor approval** (void, discount, reopen…): the request carries the approver's credentials (code + PIN). The server verifies the approver has the permission in that store, and the audit row records both operator and approver. The pattern is built in R1, first used in R2.
- **D29 — Support access** (§5): a time-boxed account an admin enables, with audit. Deferred to R3 unless you want it in R1.

### Bootstrap
- **D30 — Company is created at first startup**, not via API. Properties `restaurantpro.bootstrap.company.{name,tax-id,country,currency}` and `restaurantpro.bootstrap.admin.{username,initial-password-file}`, set from env `RESTAURANTPRO_BOOTSTRAP_COMPANY_{NAME,TAX_ID,COUNTRY,CURRENCY}`, `RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME`, `RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE` (secret file). A single-row constraint (`id boolean PRIMARY KEY CHECK (id)`) makes a second company impossible. The admin must change the initial password at first login. After bootstrap: `GET/PUT /backoffice/company-profile` only.

### Catalog publication (spec §5, §8.3)
- **D31 — Working copy + frozen release.** Admins edit normal relational tables (families, items, variants, units, barcodes, tax categories/rates, price lists, production destination). `ConfigurationRelease` follows DRAFT → VALIDATED → PUBLISHED → RETIRED. **Publish freezes a complete per-store snapshot as `jsonb`** with version, effective time and author. The previous published release becomes RETIRED. POS reads only `GET /pos/catalog` (current published snapshot plus its version). Orders (R2) copy description/price/tax from the snapshot onto the line at confirmation. So republishing can never change past sales (acceptance criterion §14 #7).
  - Alternative rejected: versioning every row (SCD-2). That means more tables, harder queries, and the same guarantee.
- Item availability ACTIVE/INACTIVE is separate from release state (§8.3).

### Operations (§11)
- **D32 — R1 ships** `deploy/compose.yaml` (backend + PostgreSQL + Caddy reverse proxy with internal TLS for LAN HTTPS), healthchecks, persistent volumes, and `deploy/backup.sh` / `deploy/restore.sh` (`pg_dump -Fc` + files, optional encryption with `age`). A test restores into a fresh container and checks the data. WAL/PITR (the RPO 15 min target) is a later ops slice.
- **D33 — Observability:** Spring Boot structured JSON logs, a correlation-id filter (`X-Correlation-Id` → MDC), and Actuator `health`/`info`/`metrics`/`prometheus` on the management port, admin-only. No `order_id` in metric labels.

---

## 6. R1 slices (vertical, each with its own tests)

| slice | content | write scope | blocked by | runs in |
|---|---|---|---|---|
| **S0 Platform** | pom deps; `shared` (Money, errors, idempotency, Clock, pagination); `audit` (append-only + trigger); security skeleton (session JDBC, CSRF, `AccessPolicy` + `Permission`, `@WithActor` test support); Testcontainers config; Modulith verify + ArchUnit rules; Flyway setup; structured logs + correlation id; dev `compose.yaml` with Postgres only | `pom.xml`, `shared/`, `audit/`, `config/`, `src/test/.../architecture`, `db/migration/shared`, `db/migration/audit` | — | alone (prefactor) |
| **S1 Organization** | bootstrap; company-profile GET/PUT; stores (timezone, cutoff, currency check); terminals + enrollment code; `OrganizationApi` | `organization/`, `db/migration/organization` | S0 | ∥ with S5 |
| **S5 Deploy & backup** | production compose + Caddy + backup/restore scripts + restore test | `deploy/` | S0 | ∥ with S1 |
| **S2 Identity** | users/employees, roles (seeded), store assignments, admin login, POS PIN login on terminal, lockout/unlock, reset-pin, sessions list/revoke, approver verification | `identity/`, `db/migration/identity` | S1 | ∥ with S3, S4 |
| **S3 Dining config** | room areas, tables (backoffice CRUD), `GET /pos/tables` (read only; occupation in R2) | `dining/`, `db/migration/dining` | S1 | ∥ with S2, S4 |
| **S4 Catalog** | families/subfamilies, items, variants, units, barcodes, tax categories/rates, price lists, production destination, releases + publish + `GET /pos/catalog` | `catalog/`, `db/migration/catalog` | S1 | ∥ with S2, S3 |

S3 and S4 code against `AccessPolicy` (the interface from S0), so they do not wait for S2. The S0 test support lets their tests act as any role. After S2 merges, a short **S6 integration pass** runs the full suite and one end-to-end test: bootstrap → admin login → create store → enroll terminal → create item → publish → POS PIN login → `GET /pos/catalog`.

**Graph**

```
F0 ✔ → F2 (this doc, you approve = G1) → F4 PRD + F5 slices (you approve = G2+G3)
  → S0 → {S1 ∥ S5} → {S2 ∥ S3 ∥ S4} → S6 integrate → F9 architecture review → G7 (you merge)
each Sx: build (developer agent, TDD, own worktree) → G4 full suite green → G5 fresh code-reviewer
```

Parallel rules check: no shared write sets (separate packages and migration blocks; only S0 touches `pom.xml`); each slice has its own `rtk mvn test -Dtest=…` that can go red alone; no slice is interactive; merges back one at a time with the full suite after each.

---

## 7. R1 acceptance criteria (observable)

1. Fresh DB + bootstrap env → exactly one company. A second bootstrap attempt or `POST` creating a company is impossible (no endpoint; DB constraint).
2. An admin logs in with a password; the session cookie is `HttpOnly`, `Secure`, `SameSite`; a mutating request without the CSRF token → 403.
3. A POS PIN login works only from an enrolled terminal; the 5th wrong PIN locks the employee; the lock is audited.
4. An employee assigned to store A with role Waiter gets **403** calling a BackOffice catalog write or a store-B resource via the API (§14 #9).
5. Two concurrent `PUT`s on the same store with the same version → one 200, one **409**.
6. Publish catalog v1, then change the price and publish v2 → `GET /pos/catalog` returns v2; release v1's snapshot is unchanged and RETIRED (§14 #7 groundwork).
7. Repeating `POST …/releases/{id}/publish` with the same `Idempotency-Key` → one publication and the same response; the same key with a different body → 422.
8. Every write in R1 creates an `audit_event`; `UPDATE`/`DELETE` on `audit_event` fails at DB level.
9. `ApplicationModules.verify()` and the ArchUnit rules pass; an injected illegal dependency makes them fail.
10. `backup.sh` then `restore.sh` into an empty container → the restored DB answers the same `GET /backoffice/stores`.

---

## 8. Process for this run

- **Agents:** `software-architect` builds S0. `developer` builds S1–S4 (TDD, isolated worktrees, in parallel per the graph). A general agent builds S5. `code-reviewer` (a fresh context, never the author) does G5 on every slice. F9 is the architecture review at the end.
- **Quality tools (UB crapper/dryer/mutator): not installed** → G4 evidence records "n/a". No metric will be claimed.
- **Commits** are small, with why / what / proof, on `feat/backend-stage1` and slice branches. **No push, no merge to `main`**: you do G7.
- **Ledger:** `.scratch/sdlc/restaurantpro-backend-r1/RUN.md` (add `.scratch/` to `.gitignore`).
- **Glossary:** after approval, `CONTEXT.md` (canonical PT↔EN terms from §8.1). The key decisions above become ADRs in `docs/adr/` (D5, D6, D13, D19, D20, D24–D27, D31).

## 9. What I need from you

1. **B1:** may I `git init` inside `restaurantPRO/` (and commit the existing scaffold + docs as the first commit)?
2. **B2:** start Docker.
3. **Scope:** approve R1 = Foundation + Stage 1 (R2–R4 as later runs).
4. **Decisions:** approve D1–D33 and P1–P6, or list overrides by id. Especially check **P1** (country), **D26** (MFA deferred), **D31** (jsonb release snapshot).
5. **Gates:** may your approval of this document also count as G2 (PRD) and G3 (slices) for R1? I would then write the PRD and issue files from §6–7 without another stop.
