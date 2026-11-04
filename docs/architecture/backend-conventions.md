# Backend conventions

Owner: `software-architect`. Sources: [product_description.md](../product_description.md) (§6, §7, §9, §10, §12), [backend-decisions.md](../design/backend-decisions.md) (Dn), [PRD R1](../prd/r1-foundation-stage1.md). Each rule names the test that enforces it. A rule without a test is a wish.

## Modules

- One module per direct subpackage of `com.xavier.backend`. Every module has a `package-info.java` with `@ApplicationModule(allowedDependencies = {...})` following D13. Name only modules that already exist: Modulith rejects an unknown name. **Enforced by** `ModularityTests.modulesRespectTheirDeclaredBoundaries`. The `ModularityTests.anIllegalModuleDependencyIsDetected` test proves that verify fails.
- `shared` is `Type.OPEN`. It holds only technical types (D14) and depends on nothing.
- `access` holds the authorization primitives `com.xavier.backend.access.Permission`, `com.xavier.backend.access.Actor` and `com.xavier.backend.access.AccessPolicy` (D13 amendment, S0b). It depends only on `shared`, and its root types are public API for every module, so `organization` and `identity` can both use it without a cycle.
- `config` holds the security filter chains and the correlation-id filter. Nothing depends on it.
- Public API is the module's **root package** plus any `@NamedInterface` (`model/dto`, `model/event`, a `<Module>Api` facade). Entities, repositories, services and controllers stay in subpackages and are internal (D12).
- Module docs are written on every test run to [modules/](modules/) (`ModularityTests.generatesModuleDocumentation`, a generator that also asserts each file exists). Commit them when they change.

Module layout (D12): `model/`, `model/dto/`, `model/event/`, `repository/`, `service/`, `controller/`, `exception/`. Create a package only when it has content.

## Layer rules (ArchUnit, `ArchitectureTests`; `ArchitectureRulesFireTest` proves each rule fires)

| Rule | Test |
|---|---|
| Controllers never depend on repositories or entities | `controllersDoNotTouchRepositoriesOrEntities` |
| Repositories (`..repository..`, `@Repository`, Spring Data) are used only by services (`..service..` or `@Service`) | `repositoriesAreUsedOnlyByServices` |
| `dto`/`event` packages never contain or reference `@Entity`/`@Embeddable` | `entitiesStayOutOfDtosAndEvents` |
| No `double`/`float` fields anywhere, none returned from `model` (use `Money`/`BigDecimal`, D15) | `noFloatingPointFields`, `noFloatingPointInModelSignatures` |
| No zero-arg `Instant.now()`/`LocalDate.now()`/...: inject `Clock` (D16) | `timeComesFromTheClock` |
| Constructor injection only (no `@Autowired` fields) | `noFieldInjection` |
| `model/event` types are records | `eventsAreRecords` |

## Shared contracts (S0)

- **Money**: `com.xavier.backend.shared.Money(BigDecimal amount, Currency currency)`. Construction never rounds and rejects excess decimals. `times(BigDecimal)` is the only rounding point (HALF_UP). `allocate(int)` and `allocate(List<BigDecimal>)` use largest remainder, so the parts always sum to the total. Mixing currencies throws `IllegalArgumentException`. Tested by `MoneyTest`.
- **Errors** (`shared.exception`): extend `NotFoundException` (404), `ConflictException` (409) or `BusinessRuleException` (422), one subclass per business meaning. A stale `@Version` (`OptimisticLockingFailureException`) gives 409. Bean Validation gives 400 with `errors: [{field, message}]`. `AccessDeniedException` gives 403 and `AuthenticationException` gives 401. Anything else gives 500 with no internal detail. Every error body is an RFC 9457 `ProblemDetail`. Tested by `GlobalExceptionHandlerTest` and `SecurityConfigTest`.
  - A 422 that lists several problems throws a Spring `ErrorResponseException` whose `ProblemDetail` carries an `errors` property; the handler keeps its properties, which a `BusinessRuleException` would lose (e.g. `catalog.ReleaseValidationFailedException`).
- **Time**: inject `java.time.Clock` (UTC). Tests pin it with a `@TestConfiguration` that declares a `@Primary Clock`.
- **Pagination**: accept a `Pageable` and return `PageResponse.from(page)`. Size is capped at 200 (`PageResponseTest`).
- **Idempotency** (D24): controllers of repeatable commands return
  `idempotencyService.execute("<module>.<operation>:" + actorId, key, request.getMethod() + " " + request.getRequestURI(), body, () -> ResponseEntity...)`, with `key` from `@RequestHeader("Idempotency-Key")`. Call it from the controller, outside any transaction: called inside one it throws `IllegalStateException` before claiming the key. The request identity is the SHA-256 of `target` plus the canonical JSON body.
  - Same key and same target+body → the stored response is replayed.
  - Same key with a different target or body → 422.
  - A duplicate while another request holds the key → 409. Only SQLSTATE 55P03 (lock_not_available) means that; a deadlock (40P01) or serialization failure (40001) propagates.
  - A missing, blank or over-255-character key → 400.
  - Crash-safe: the action runs only while holding the record's row lock (`FOR UPDATE NOWAIT`), and its effects and the stored response commit together. After a crash or a failed action, the next request with that key takes the claim over and runs it once.
  - Never write your own (`IdempotencyServiceTest`).
  - Known limits: a replay returns the status and JSON body only, no headers such as `Location`; a `String` body comes back as a JSON string. There is no retention yet. The action runs inside a DB transaction, so R2 external calls (payment, fiscal) need another pattern (spec §7).
- **Audit** (D20): call `AuditLog.record(new AuditEntry(...))` inside the use case's `@Transactional` method. It requires an existing transaction (`MANDATORY`). Details carry no PIN, password, token or card data, and keys that look like secrets are rejected. The secret-key guard checks top-level keys only. The database refuses UPDATE, DELETE and TRUNCATE on `audit.audit_event` (`AuditLogTest`).
- **Authorization** (D27): the first line of every use case is `accessPolicy.require(Permission.X, storeId)` (or `require(Permission.X)` for installation-wide operations). Controllers never authorize. Authorities are `PERM_X` (all stores) and `PERM_X@<storeId>`. `Actor.authorities()` is the only source for these decisions; S2 must build the `Authentication`'s granted authorities from the same set (actuator URL rules read those). Denials are logged at WARN (`AccessPolicyTest`, `SecurityConfigTest`).

## Persistence

- Flyway, one history, `db/migration/<module>/V<block>__<module>_<what>.sql` with blocks shared/audit 1xxx, organization 2xxx, identity 3xxx, dining 4xxx, catalog 5xxx (D5). Never edit an applied migration.
- One schema per module (`CREATE SCHEMA IF NOT EXISTS <module>`), with no cross-module foreign keys (D6). Constraints back every invariant they can express.
- `spring.jpa.hibernate.ddl-auto=validate`: entities must match the migrations, and every context start checks it.
- UUIDv7 ids: `@UuidGenerator(style = VERSION_7)`.

## Events

- Cross-module events go through the Spring Modulith JPA publication registry (`event_publication`, D19). Listeners use `@ApplicationModuleListener` and must be idempotent.
- `spring.modulith.events.republish-outstanding-events-on-restart=true`: when the application starts, it delivers again every publication whose listener had not completed. This is safe because each installation runs one instance. `EventRepublicationTest` proves it with a real second application context started on the same database.

## Web and security

- Routes: `/api/v1/auth/**` (open), `/api/v1/pos/**`, `/api/v1/backoffice/**` (authenticated session). POS and BackOffice call the same service.
- CSRF uses the SPA pattern. A `GET` sets the readable `XSRF-TOKEN` cookie, and mutating requests send it back in `X-XSRF-TOKEN`. In MockMvc tests use `.with(csrf())`, or send the cookie and header as `SecurityConfigTest` does.
- Sessions are stored in PostgreSQL (`shared.spring_session`). The cookie is `Secure; HttpOnly; SameSite=Lax`, and Boot applies this only on a real server (`WebServerSecurityTest`).
- Actuator runs on the management port (8081 by default). `health` is open. Every other endpoint needs `PERM_SYSTEM_MONITOR`.
- Every response carries `X-Correlation-Id`, and every JSON (ECS) log line carries `correlationId` (`CorrelationIdTest`).

## Tests

- Real PostgreSQL only: `@Import(TestcontainersConfiguration.class)`. One `postgres:18.6-alpine` container serves the whole JVM. Tests share the database, so use fresh UUIDs and keys per test and never assume empty tables.
- Module tests: `@ApplicationModuleTest(extraIncludes = {"shared", "access", "audit"})` + `@Import(TestcontainersConfiguration.class)`. A module test boots only its own module: `shared` holds the `Clock` and JSON beans, `access` holds `AccessPolicy`, `audit` holds `AuditLog`. Add the modules your code calls.
- Act as a user with `@WithActor(permissions = {...}, storeId = "...")` (`com.xavier.backend.access.WithActor`, test sources).
- Throw-away controllers for HTTP tests are static nested `@RestController`s pulled in with `@Import(Test.Probe.class)`. Spring's test scan skips nested classes of test classes.
- Architecture fixtures live in `com.xavier.archfixture.*`, outside the scanned base package.
