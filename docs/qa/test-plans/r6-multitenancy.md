# Test plan — R6 SaaS multi-tenancy (integration, S76)

Sources: [PRD r6-multitenancy](../../prd/r6-multitenancy.md) (A80–A92, the 10 security rules), [multitenancy-decisions](../../design/multitenancy-decisions.md) (D174–D190, §3 failure modes), ADR 0040. Slice-level tests are the slices' (S71–S75, S73); this plan covers what only the integrated tree can prove, and links the rest.

## Scope and risks

- **Cross-tenant leak** (money, fiscal documents, customers): one forgotten filter, request field, cache key or job without a tenant. Highest risk; covered at three levels (HTTP, application-role seam, coverage of the migrations).
- **Tenant from the request** (forged `X-Tenant-Id`, body or path value) and existence oracles (foreign vs random id).
- **Devices** (terminal and bridge tokens, `X-Pos-Session`, print jobs claimed by the wrong store or tenant).
- **Lifecycle** (suspension takes effect on the next request and for jobs; onboarding idempotent).
- **Pooling** (`SET LOCAL` semantics behind PgBouncer), **operations** (export/restore of one tenant, backup/PITR, upgrade from R5), **performance** at 20 tenants × 10 terminals.

## Scenarios → cheapest level

| scenario | level | test |
|---|---|---|
| A and B onboarded by the platform API with identical store, item, employee, register, series, terminal and bridge codes; each sells | system (HTTP) | `e2e.R6AcceptanceTest` |
| Lists of every route family (BackOffice stores, employees, roles, items, price lists, series, terminals; POS floor map) and the sales report show only the caller's tenant | system | `R6AcceptanceTest.onlyOwn`, `salesOfPrego` |
| Another tenant's resource by id → 404; own other store → 403; another tenant's store id as a filter answered exactly as a random id | system | `R6AcceptanceTest` (`sameAsRandom`); open question [F25](../../issues/r6-followups/F25-a82-store-filter-of-another-tenant.md) |
| Forged `X-Tenant-Id` (foreign and random: one body), body `tenantId` ignored | system + unit | `R6AcceptanceTest`; `TenantResolutionTest`, ArchUnit `noTenantIdFromRequests` |
| Accountant with two memberships: selection required, list of own tenants only, each selection sees its tenant | system | `R6AcceptanceTest`; `TenantListTest`, `TenantSelectionRequiredTest` |
| Support with a 1 h grant in A acts in A only; B as a random tenant | system | `R6AcceptanceTest`; `SupportAccessTest` |
| Listener chain round → ticket → consumption → reporting under the event's tenant | system | `R6AcceptanceTest` step 2; `ActorlessTenantScopeTest`, module `ActorlessScopeTest`s |
| A bridge claims only its store's jobs, never another tenant's | system + module | `R6AcceptanceTest` step 3; `BridgeJobClaimTest` |
| Suspension mid-test: manager, admin, POS, bridge, accountant 403 `TENANT_SUSPENDED`; A unaffected; jobs stop leasing for B | system | `R6AcceptanceTest` step 7; `PlatformTenantLifecycleTest`, `ActorlessTenantScopeTest` |
| Platform API refuses tenant tokens; platform tokens reach no business data | system | `R6AcceptanceTest`; `PlatformAuthorizationTest` |
| Database isolation without `AccessPolicy` (reads, inserts, updates, children) | application-role seam | the 15 `<Module>TenantIsolationTest`s |
| Every schema converted; keys include the tenant; definer allow-list | migrations | `RowLevelSecurityCoverageTest` (strict), `CompositeForeignKeyCoverageTest` (strict) |
| `SET LOCAL` semantics; PgBouncer transaction mode | unit + drill | `TenantBindingTransactionManagerTest`; `deploy/drills/pgbouncer/run.sh` |
| JWT validation complete; Keycloak down at start | unit + IT | `JwtValidationTest`, `IdpUnavailableTest`, `KeycloakLoginIT` |
| Terminal enrolment, revocation, PIN | module | `PosTerminalAuthTest`, `TerminalEnrolmentThrottleTest`, `TerminalRevocationTest` |
| Step-up once with the same `Idempotency-Key` | module | `StepUpTest` |
| Tenant export/restore; backup/restore; PITR; upgrade from R5; two installations | drills | `deploy/test-tenant-export.sh`, `test-tenant-restore.sh`, `test-backup-restore.sh`, `test-pitr.sh --quick`, `test-upgrade-roles.sh`, `test-two-installations.sh` |
| p95 < 500 ms at 20 × 10 with a no-RLS baseline | load | `e2e.R6TenantLoadSmoke` ([deploy/load/r6-load-plan.md](../../../deploy/load/r6-load-plan.md)) |

## Test data

Fresh PostgreSQL per system class (`Installation.FreshDatabase`); tenant codes and subjects suffixed per run; the Keycloak admin port faked at the module boundary (`RecordingIdentityProvider`); tokens from `TestJwtFactory`.

## Mutants run (S76)

- `DROP POLICY tenant_scope ON catalog.item` → `R6AcceptanceTest` red (B's item listed to A).
- `tenancy.active_tenant_ids()` returning SUSPENDED too → `R6AcceptanceTest` red (a lease of B after its suspension).
- `catalog.item` registered UNSCOPED → `RowLevelSecurityCoverageTest.everySchemaIsConvertedAndNothingIsLeftUnscoped` red.

## Not covered here (why)

- Real Keycloak in the journey: tokens are test-signed; the real IdP is `KeycloakLoginIT` (follow-ups F15, F16 for logout paths).
- Frontend (POS/BackOffice SPA changes are a later run).
- Multi-instance BFF (F04), lease renewal for long runs (F05), B8 on AWS (F17).
