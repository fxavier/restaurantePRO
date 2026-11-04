# PRD R1 — Foundation + Stage 1 (backend)

Approved 2026-11-04 (with [backend-decisions.md](../design/backend-decisions.md), referenced below as Dn). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md).

Base package `com.xavier.backend`. Per-module layout D12. Flyway blocks D5. All slices: TDD, no `@Disabled`, full `rtk mvn test` green at exit.

---

## S0 — Platform (prefactor, alone)

The contracts below are what S1–S5 build against. Names are binding; internals are the implementer's call.

**Build (`backend/pom.xml`, the only slice that edits it):** the deps in decisions §3, versions from the Boot BOM; Spring Modulith BOM and springdoc pinned to Boot-4.1-compatible releases (verify on Maven Central).

**`shared` (OPEN module, `@ApplicationModule(type = OPEN)`):**
- `Money` record (`amount` BigDecimal, `currency` java.util.Currency): `of`, `plus`, `minus`, `times(BigDecimal qty)` rounding HALF_UP to currency minor units, `allocate(int parts)` / `allocate(List<BigDecimal> weights)` largest-remainder; rejects currency mix.
- Exceptions: `NotFoundException` (404), `ConflictException` (409), `BusinessRuleException` (422), plus `OptimisticLockingFailureException` → 409, validation → 400 with field errors. One `@RestControllerAdvice` producing `ProblemDetail`.
- `Clock` bean (UTC system clock), overridable in tests.
- `PageResponse<T>(content, page, size, totalElements)`; max page size 200.
- Idempotency (D24): `IdempotencyService.execute(scope, key, requestBody, Supplier<ResponseEntity<?>>)` or an equivalent annotation-driven mechanism; table `shared.idempotency_record` unique `(scope, key)`; replay same hash, 422 on different hash, 409 while in progress.
- Migrations `db/migration/shared/V1001__…`.

**`audit`:** `AuditLog.record(AuditEntry)` public API, writes in caller's transaction (D20). `AuditEntry(actorId, approverId, action, entityType, entityId, storeId, terminalId, reason, Map<String,Object> details)`. Table `audit.audit_event` with trigger rejecting UPDATE/DELETE. Migrations `db/migration/audit/V1101__…`.

**`access` module (authorization primitives; moved out of `identity` by S0b, see D13 amendment):**
- `Permission` enum with at least: `COMPANY_MANAGE, STORE_MANAGE, TERMINAL_MANAGE, USER_MANAGE, ROLE_MANAGE, EMPLOYEE_MANAGE, DINING_MANAGE, DINING_VIEW, CATALOG_MANAGE, CATALOG_PUBLISH, CATALOG_VIEW, AUDIT_VIEW`. Later runs append.
- `Actor` record (`userId`, `employeeId` nullable, `terminalId` nullable, `storeId` nullable, `Set<String> authorities`).
- `AccessPolicy` with `Actor current()`, `void require(Permission p)` (installation-wide), `void require(Permission p, UUID storeId)`; throws `AccessDeniedException` → 403. S0 implementation reads the Spring Security context: authority strings `PERM_<NAME>` (global) and `PERM_<NAME>@<storeId>` (store-scoped). S2 (identity) keeps this contract and puts an `Actor` with the authorities into the session at login. Package `com.xavier.backend.access`.
- Test support: `@WithActor(permissions = {...}, storeId = "...")` (a `WithSecurityContextFactory`) so S1/S3/S4 tests act as any role without S2.

**Security config:** Spring Security filter chain: `/api/v1/auth/**` login endpoints permitted, everything else authenticated; Spring Session JDBC (its schema via Flyway in shared block); cookie Secure/HttpOnly/SameSite=Lax; CSRF with `CookieCsrfTokenRepository` (SPA). 401 as ProblemDetail. Actuator on management port, admin-only except `health`.

**Observability:** structured JSON logging (Boot native), `X-Correlation-Id` filter → MDC + response header.

**Tests / enforcement:** `TestcontainersConfiguration` (`@ServiceConnection` PostgreSQL, pinned image, reused by all tests); `ModularityTests` (`ApplicationModules.verify()` + `Documenter` to `docs/architecture/modules/`); ArchUnit: controllers don't touch repositories/entities, no `double`/`float` fields named or typed for money in `model`, entities absent from `dto`/`event`. `BackendApplicationTests` keeps passing.

**Dev:** `backend/compose.yaml` with PostgreSQL only (Spring Boot docker-compose support optional — do not add the dependency unless trivial).

**Acceptance:** Money unit tests (allocation sums to total, HALF_UP, currency mix rejected); idempotency integration test (replay, 422, concurrent duplicate); audit trigger test (UPDATE/DELETE fail); `@WithActor` + `AccessPolicy` test (403 without permission, 403 wrong store); unauthenticated request → 401 ProblemDetail; POST without CSRF → 403; modularity verify passes.

---

## S0b — Extract `access` module (alone, before S1)

Move `Permission`, `Actor`, `AccessPolicy` from `com.xavier.backend.identity` to `com.xavier.backend.access` (new OPEN-or-named-interface module, → shared only), with `@WithActor` + its factory and `AccessPolicyTest` in test `com.xavier.backend.access`. Update `config` (SecurityConfig) and any package-info `allowedDependencies`; delete the empty `identity` package (S2 recreates it). Update docs/architecture/backend-conventions.md. Behaviour unchanged: full suite green, ModularityTests green.

## S1 — Organization (∥ S5)

Migrations `db/migration/organization/V2xxx`. Public `OrganizationApi`: `Optional<StoreSummary> findStore(UUID)`, `boolean storeExists(UUID)`, `ZoneId zoneOf(UUID)`, `Optional<TerminalSummary> findTerminalByDeviceToken(String)`, `CompanyProfileView companyProfile()`.

- **Bootstrap (D30):** `ApplicationRunner` reads `restaurantpro.bootstrap.company.*` (name, taxId, country ISO-3166, currency ISO-4217); creates the singleton if absent; idempotent on restart; table enforces single row. Publishes `CompanyBootstrapped` event (identity creates the admin from `restaurantpro.bootstrap.admin.*` in S2).
- `GET/PUT /api/v1/backoffice/company-profile` (`COMPANY_MANAGE`, `@Version` → 409). Currency immutable after first store exists (422).
- Stores: `GET/POST /api/v1/backoffice/stores`, `GET/PUT /stores/{id}`; fields code (unique), name, timezone (valid ZoneId), businessDayCutoff (LocalTime, default 05:00), active. Currency = company currency (single currency). `STORE_MANAGE`, store-scoped read via `STORE_MANAGE@store`.
- Terminals: `GET/POST /api/v1/backoffice/terminals`, `POST /terminals/{id}/enrollment-code` (returns one-time code once, stored hashed, expires 24h), `POST /api/v1/auth/terminals/enroll` (code → device token returned once, stored hashed; code consumed), `POST /terminals/{id}/revoke`. `TERMINAL_MANAGE`.
- Business-day rule: pure function `BusinessDays.of(Instant, ZoneId, LocalTime cutoff) → LocalDate`, unit-tested across midnight and DST.
- Every write audited.

**Acceptance:** criteria 1, 5 of decisions §7; enrollment code single-use + expiry; device token never returned twice nor stored in plain text; business-day cases.

## S5 — Deploy & backup (∥ S1)

Write scope `deploy/` only. `deploy/compose.yaml` (backend image built from `backend/` via Spring Boot buildpacks or a Dockerfile in `deploy/`, PostgreSQL pinned, Caddy with `tls internal`, healthchecks, named volumes, restart policies, secrets via Docker secrets/env file — `.env.example` only, never real values). `deploy/backup.sh` (pg_dump -Fc + config, optional `age` encryption, timestamped, retention N), `deploy/restore.sh` (into a target DB, refuses non-empty target unless `--force`). `deploy/test-backup-restore.sh`: spins a throwaway Postgres, loads sample data, backs up, restores into a second container, compares row counts/checksums; exits non-zero on mismatch. `deploy/README.md` short operator runbook.

**Acceptance:** criterion 10 (using sample SQL now; re-run against the real schema at S6).

## S2 — Identity (∥ S3, S4)

Migrations `db/migration/identity/V3xxx`. identity → shared, audit, access, organization. Builds the `Actor` principal consumed by `access.AccessPolicy`. **POS login grants only the terminal's store authorities** (D27 amendment); BackOffice login grants all assigned stores (+ global grants for the admin role, including `SYSTEM_MONITOR`).

- Admin bootstrap from `restaurantpro.bootstrap.admin.username` + `initial-password-file`; `mustChangePassword=true`.
- `POST /api/v1/auth/login` (username+password, BCrypt) ; `POST /api/v1/auth/pos-login` (employee code + PIN + `X-Terminal-Token` header; terminal must be enrolled & active, employee assigned to the terminal's store); `POST /auth/logout`; `GET /auth/session` (actor, store, terminal, permissions); `POST /auth/password` (change).
- PIN: 4–8 digits, BCrypt; 5 consecutive failures → employee locked (audited); manager unlock `POST /backoffice/employees/{id}/unlock`; `POST /employees/{id}/reset-pin`.
- `/api/v1/backoffice/users`, `/employees`, `/roles` CRUD; `/employees/{id}/assignments` (store + role). Seeded roles for the 7 profiles of spec §3 (Flyway data).
- Session authorities built from assignments: `PERM_X@storeId`; admin role → global `PERM_X`.
- Sessions: `GET /backoffice/users/{id}/sessions`, `DELETE …/sessions/{sessionId}` via `FindByIndexNameSessionRepository`. Session fixation protection on login.
- `ApproverVerifier.verify(employeeCode, pin, Permission, storeId) → UUID approverId` public API (D28), counts toward lockout.
- Logs never contain PIN/password.

**Acceptance:** criteria 2, 3, 4 of decisions §7; locked employee cannot log in; password change clears `mustChangePassword`.

## S3 — Dining configuration (∥ S2, S4)

Migrations `db/migration/dining/V4xxx`. `/api/v1/backoffice/areas`, `/tables` CRUD (store-scoped, `DINING_MANAGE`; table: code unique per store, area in same store, seats > 0, active). Store existence via `OrganizationApi`. `GET /api/v1/pos/tables?storeId=` (`DINING_VIEW@store`) returns areas + tables with status `FREE` (occupation arrives in R2). `@Version` on area/table. Audited.

**Acceptance:** cross-store reference rejected (table in store A with area of store B → 422); 403 for other store; 409 on stale version.

## S4 — Catalog (∥ S2, S3)

Migrations `db/migration/catalog/V5xxx`. Store-scoped where the spec scopes (price lists per store; items company-wide with per-store availability — implementer may simplify to company-wide items + store-scoped price lists and must say so).

- Families/subfamilies, units, tax categories/rates (rate as decimal percent, effective-from), items (code unique, name, family, unit, tax category, production destination code, ACTIVE/INACTIVE, sold-by-weight flag), variants, barcodes (unique), price lists + item prices (`Money`).
- Releases (D31): `POST /backoffice/catalog/releases` (creates DRAFT for a store), `POST /releases/{id}/validate` (checks: every active item has price and tax; returns errors as 422 list), `POST /releases/{id}/publish` (requires VALIDATED, `CATALOG_PUBLISH@store`, `Idempotency-Key` required): freezes jsonb snapshot, sets effective instant + author, retires previous PUBLISHED for that store, publishes `CatalogPublished(storeId, releaseId, version)` event. Release state transitions only via aggregate methods; illegal → 409.
- `GET /api/v1/pos/catalog?storeId=` → current published snapshot + version + effectiveAt (`CATALOG_VIEW@store`); 404 if none.
- Audited (publish includes author).

**Acceptance:** criteria 6, 7 of decisions §7; illegal transitions (publish a DRAFT) → 409; editing working copy after publish doesn't change the published snapshot.

## S6 — Integrate (main, after all merge)

Full suite + one end-to-end `@SpringBootTest` over HTTP (MockMvc with real security): bootstrap → admin login (+CSRF) → change password → create store → create terminal + enroll → create employee + assignment → create item/price/tax → release validate/publish → POS PIN login with terminal token → `GET /pos/catalog`. Re-run `deploy/test-backup-restore.sh` against the real schema.
