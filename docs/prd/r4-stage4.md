# PRD R4 — Stage 4 "Gestão e extensões" (backend)

Status: **APPROVED** 2026-11-06, together with [r4-decisions.md](../design/r4-decisions.md) (cited as Dn and Pn). Earlier decisions: [backend-decisions.md](../design/backend-decisions.md), [r2-decisions.md](../design/r2-decisions.md), [r3-decisions.md](../design/r3-decisions.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md). Conventions: [backend-conventions.md](../architecture/backend-conventions.md).

Base package `com.xavier.backend`, per-module layout D12, Flyway blocks D135. Every slice is built with TDD, **red first**: the slice's named tests are written and fail before the code, and they are deterministic (an injected `Clock`, fixed ids where order matters, no sleeps; concurrency tests use latches and real PostgreSQL). No `@Disabled`, and `cd backend && rtk mvn clean test` must be green at exit with **0 skipped**.

- **Every slice PR targets `main`, and none is stacked.** A slice branches from `main` once every slice it is blocked by has merged.
- Slices merge one at a time, in ascending slice number within a group, with the full suite after each merge.
- Slices **do not commit `docs/architecture/modules/`** (D44). Only S54 does.
- **No slice edits `pom.xml`.** R4 needs no new Maven dependency.
- The names below are binding. Internals are the implementer's call.

Conventions for every endpoint below, as in R2 and R3:
- **POS:** `requirePos(...)`, with the store and terminal taken from the session (D39).
- **Idem:** `Idempotency-Key` through `access.IdempotentRequests` (`execute`, or `steps` for D63).
- **Approver:** the `X-Approver-Code` and `X-Approver-Pin` headers, verified before the transaction (D40); missing or invalid → 403.
- **Errors:** a stale version gives 409. Business rules give 422 `ProblemDetail` with a stable `code`, and every new code is added to `CodedErrorContractTest`.
- **Store scope:** D42 order. Company-wide resources (suppliers, stock items, recipes, loyalty program) are read with `requireInAnyStore`; supplier and stock-item writes need the permission installation-wide (`accessPolicy.require(X)`, D125 amendment).
- **Audit:** every write calls `AuditLog.recordAsCurrentActor` (D41) or `recordAsSystem` (jobs, listeners).

---

## S37 — Platform prefactor (runs alone, first)

**Write scope:**
- `access/Permission.java`, `db/migration/identity/V3012__identity_r4_permissions.sql`
- `promotions/package-info.java` and the pricing contract records in `promotions/model/dto/` (with its `@NamedInterface("dto")` package-info); `inventory/package-info.java` and `inventory/model/event/StockMovementsPosted.java` (with its `@NamedInterface("event")` package-info). These are real types, so Modulith sees both new modules from S37 on, and the downstream slices code against frozen contracts.
- `orders/OrdersApi.java`, `orders/BatchFacts.java`, `orders/VoidFacts.java` and the orders services behind them (read-only additions)
- `catalog/CatalogApi.java` (+ `catalog/ItemRef.java`), `customers/CustomersApi.java` (+ `customers/AddressSnapshot.java`)
- the `package-info.java` of `orders`, `dining` and `reporting` (D99 edges)
- `src/test/.../architecture`, `docs/architecture/backend-conventions.md` (module and rule rows)

| contract | detail |
|---|---|
| `access.Permission` | Appends the D134 constants. V3012 grants them to the administrator and to the default roles of the D134 table. |
| `promotions/package-info.java` | `allowedDependencies = {"shared", "audit", "access", "organization", "organization :: dto", "catalog", "catalog :: dto", "customers", "customers :: dto"}`. |
| `promotions.model.dto` (contracts, frozen) | `PricingRequest(UUID storeId, Instant at, String modeCode, boolean member, List<PricingLine> lines)`, `PricingLine(UUID key, UUID itemId, UUID variantId, UUID familyId, Money listUnitPrice, BigDecimal quantity)`, `PricingResult(UUID releaseId, Long version, List<PricedLine> lines)`, `PricedLine(UUID key, Money listUnitPrice, Money unitPrice, AppliedPromotion promotion)`, `AppliedPromotion(UUID promotionId, String code, String name, String kind)`, `DiscountLimits(BigDecimal freeLimitPercent, BigDecimal maxPercent)`. S39 implements the facade over them. |
| `inventory.model.event.StockMovementsPosted` (contract, frozen) | `StockMovementsPosted(String sourceType, UUID sourceId, UUID storeId, LocalDate businessDate)`. S42 publishes it; S53 consumes it. |
| `inventory/package-info.java` | `allowedDependencies = {"shared", "audit", "access", "organization", "organization :: dto", "catalog", "catalog :: dto", "catalog :: event", "orders", "orders :: event"}`. |
| `orders`, `dining`, `reporting` package-info | orders + `promotions`, `promotions :: dto`, `customers`, `customers :: dto`; dining + `customers`; reporting + `inventory`, `inventory :: event`. A name for a module that has no class yet is accepted because S37 creates both package-infos. |
| `orders.BatchFacts` (root record) | `BatchFacts(UUID batchId, UUID orderId, UUID storeId, LocalDate businessDay, OrderMode mode, Instant confirmedAt, List<BatchLineFact> lines)`; `BatchLineFact(UUID lineId, UUID itemId, UUID variantId, UUID releaseId, BigDecimal quantity, String destination, List<ComponentFact> components)`; `ComponentFact(UUID componentId, UUID itemId, UUID variantId, BigDecimal quantity, String destination)`. `components` is empty until S46. |
| `OrdersApi` | `BatchFacts batchFacts(UUID batchId)` (readOnly; 404 when missing). |
| `orders.VoidFacts` | Gains `UUID consumedLineId` as its **last** component: the batch line the voided line descends from through transfers (and, from S46, splits). R3 voids return their own line id when it has a batch. A constructor with the old components stays. |
| `catalog.CatalogApi` | `List<ItemRef> items(Collection<UUID> ids)` (readOnly, working copy): `ItemRef(id, code, name, familyId, active, List<UUID> variantIds)`. |
| `customers.CustomersApi` | `Optional<AddressSnapshot> address(UUID customerId, UUID addressId)` (readOnly): `AddressSnapshot(addressId, kind, line1, line2, city, postalCode, country, notes)`. |
| Listener-id contract | Inventory listeners live in `com.xavier.backend.inventory.consumption`; reporting R4 listeners in `com.xavier.backend.reporting.inventory`. `EventPublicationStats.pendingFor(prefix)` is used with these prefixes (S49, S53). |
| Architecture tests | D99 forbidden edges with `allowEmptyShould(true)`: `promotions ↛ {orders, settlement, fiscal, cash, production, inventory, dining, reporting}`, `inventory ↛ {settlement, fiscal, cash, customers, promotions, dining, production}`, `orders ↛ inventory`, nothing → `inventory` except `reporting`. New rule `inventoryCallsOnlyReadOnlyFacadeMethods`. Each has an `ArchitectureRulesFireTest` fixture. |

**Tests (red first):** `BatchFactsTest` (a two-line round; a transferred-in line is absent), `VoidFactsConsumedLineTest` (a void on a transferred-in line names the source batch line), `RoleSeedTest` (new permissions), `ArchitectureRulesFireTest` (each new rule fires on its fixture), `ModularityTests` green.

**Acceptance:** the rule half of A53. The full R3 suite stays green.

---

## Group 1 (after S37, in parallel): S38 ∥ S39 ∥ S40 ∥ S41 ∥ S42 ∥ S43 ∥ S44 ∥ S45

### S38 — Catalog R4: menus and operational availability
**Write scope:** `catalog/`, `db/migration/catalog/V5003+`.

**Migrations:**
- `V5003__catalog_item_kind_and_menu.sql`: `item.kind VARCHAR(10) NOT NULL DEFAULT 'STANDARD' CHECK IN ('STANDARD','MENU','FEE')`; `menu_group(id, item_id FK, code, name, min_choices ≥ 0, max_choices ≥ min_choices, position)` with `UNIQUE(item_id, code)`; `menu_option(id, group_id FK, item_id, variant_id, quantity > 0, position)` with `UNIQUE(group_id, item_id, variant_id)`.
- `V5004__catalog_quick_pages_macros_automatic.sql`: `quick_page(id, store_id NULL = all, code, name, position)`, `quick_page_entry(page_id FK, position, item_id | variant_id | macro_id)` (CHECK exactly one); `macro(id, code UNIQUE, name)`, `macro_entry(macro_id FK, position, item_id, variant_id, quantity > 0)`; `automatic_item(id, store_id NULL, trigger CHECK IN ('TABLE_OPEN_PER_GUEST','ORDER_OPEN'), mode NULL, item_id, variant_id, quantity > 0, active)`.
- `V5005__catalog_operational_availability.sql`: `item_availability(store_id, item_id, variant_id NULL, sold_out_at, sold_out_by_actor_kind, sold_out_by_actor_id, reason)` with a unique index on `(store_id, item_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'))`; `daily_dish(store_id, business_date, item_id, position)` PK `(store_id, business_date, item_id)`.

**Contracts:**
- `CatalogSnapshot.SCHEMA_VERSION = 2`. `Item` gains `kind` and `menuGroups` (`MenuGroup(code, name, minChoices, maxChoices, options[MenuOption(itemId, variantId, quantity)])`); the snapshot gains `quickPages`, `macros` and `automaticItems`. A v1 snapshot parses with `kind = STANDARD` and empty lists (`CatalogSnapshotV1CompatTest`).
- Validation (release VALIDATED): a menu's options are ACTIVE items in the same release and not MENU themselves; a FEE item has no menu groups; an automatic item is in the release.
- `CatalogApi`: `Set<ItemKey> soldOut(UUID storeId)` and `List<UUID> dailyDishes(UUID storeId, LocalDate businessDate)` (readOnly), `ItemKey(itemId, variantId)`.

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `/api/v1/backoffice/catalog/items/{id}/menu-groups` (GET, POST, PUT, DELETE) | `CATALOG_MANAGE` | POST | 422 `MENU_GROUP_INVALID` |
| `/api/v1/backoffice/catalog/quick-pages`, `/macros`, `/automatic-items` (CRUD) | `CATALOG_MANAGE` | POST | |
| `PUT /api/v1/pos/catalog/availability/{itemId} {variantId?, soldOut, reason?}` | `CATALOG_AVAILABILITY` | yes | Store from the session; SSE `CATALOG` notice; audited |
| `GET /api/v1/pos/catalog/availability` | `CATALOG_VIEW` | — | Sold-out keys of the session's store |
| `PUT /api/v1/backoffice/catalog/daily-dishes {storeId, businessDate, itemIds[]}`; `GET /api/v1/pos/catalog/daily-dishes?date` | `CATALOG_AVAILABILITY`; `CATALOG_VIEW` | PUT | 422 `ITEM_NOT_PUBLISHED` |

**Tests:** `MenuReleaseValidationTest`, `CatalogSnapshotV1CompatTest`, `SoldOutFlagTest` (store scope, SSE), `DailyDishTest`, `MacroAndAutomaticItemReleaseTest`.

**Acceptance:** the catalog half of A43.

### S39 — Promotions: releases, engine, discount limits
**Write scope:** `promotions/` except `promotions/loyalty/**`, the root `Loyalty*.java` files and the S37 contract records; `db/migration/promotions/V12001–V12019`.

**Migrations:**
- `V12001__promotions_create_promotion.sql`: `CREATE SCHEMA promotions`; `promotion(id, code UNIQUE, name, kind CHECK IN ('PERCENT_OFF','AMOUNT_OFF_UNIT','FIXED_UNIT_PRICE'), percent, amount, currency, priority, valid_from, valid_to, members_only, modes text[] NULL, active, version)` with CHECKs per kind; `promotion_scope(promotion_id FK, item_id | variant_id | family_id)` (CHECK exactly one); `promotion_window(promotion_id FK, days_of_week smallint[], local_start time, local_end time)`; `promotion_store(promotion_id FK, store_id)`.
- `V12002__promotions_create_discount_limits.sql`: `discount_limits(store_id PK, free_limit_percent CHECK 0–100, max_percent CHECK ≥ free_limit_percent and ≤ 100, version)`.
- `V12003__promotions_create_promotion_release.sql`: `promotion_release(id, store_id, status CHECK IN ('DRAFT','VALIDATED','PUBLISHED','RETIRED'), version_no, snapshot jsonb, effective_at, published_by_account_id, created_at, version)`; partial unique `(store_id) WHERE status = 'PUBLISHED'`; `UNIQUE(store_id, version_no)`; trigger: `snapshot` immutable once set.

**Contracts:**
- `promotions.model.dto.PromotionSnapshot` (schema 1): store, release id, version, currency, `discountLimits`, `promotions[]` (kind, values, priority, validity, windows, modes, scope, membersOnly).
- `promotions.PromotionEngine` (pure, root): `PricingResult evaluate(PromotionSnapshot snapshot, PricingRequest request, ZoneId zone)` per D103. Windows may cross midnight (`local_end < local_start`).
- `promotions.PromotionsApi` (readOnly, root), over the S37 records in `promotions.model.dto`:
  - `PricingResult price(PricingRequest request)`. No published release → every line at list price, `releaseId = null`.
  - `OptionalLong currentVersion(UUID storeId)`; `DiscountLimits discountLimits(UUID storeId)` (default 0 / 100, D106).
- Event `promotions.model.event.PromotionsPublished(storeId, releaseId, version)` → SSE `CATALOG` notice to the store (the POS re-reads prices).

**Endpoints** (BackOffice):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `/api/v1/backoffice/promotions` (CRUD), `/promotions/{id}/scope`, `/windows`, `/stores` | `PROMOTION_MANAGE` (`PROMOTION_VIEW` for GET) | POST | 422 `PROMOTION_INVALID` (kind values, empty scope, `valid_from ≥ valid_to`) |
| `PUT /api/v1/backoffice/promotions/discount-limits/{storeId}` | `PROMOTION_MANAGE` | yes | 422 `DISCOUNT_LIMITS_INVALID` |
| `POST /api/v1/backoffice/promotions/releases {storeId}`; `POST /releases/{id}/validate`; `POST /releases/{id}/publish` | `PROMOTION_MANAGE` | yes | Same machine and errors as catalog releases; validation: scope items exist in the store's published catalog (`CatalogApi.currentSnapshot`) |
| `GET /api/v1/pos/promotions/current` | `CATALOG_VIEW` | — | The session store's snapshot, for POS previews |

**Tests:** `PromotionEngineTest` (property: price ≤ list and ≥ 0; deterministic tie-breaks; per-unit HALF_UP, 10 % of 1.25 → 1.13), `HappyHourWindowTest` (crossing midnight, DST, store zone, fixed `Clock`), `PromotionReleaseLifecycleTest`, `PromotionSnapshotImmutableTest` (trigger), `DiscountLimitsDefaultTest`.

**Acceptance:** the engine half of A38 and A39.

### S40 — Loyalty ledger
**Write scope:** `promotions/loyalty/**`, root `promotions/LoyaltyApi.java` and the root loyalty records (`LoyaltyBalance`, `RedeemCommand`, `RedemptionResult`, `EarnCommand`, `EarnLine`, `EarnResult`); `db/migration/promotions/V12020–V12039`.

**Migrations:**
- `V12020__promotions_create_loyalty_program.sql`: `loyalty_program_version(id, version_no UNIQUE, status CHECK IN ('DRAFT','PUBLISHED','RETIRED'), spend_unit NUMERIC(19,4) > 0, currency, points_per_unit > 0, published_at, published_by_account_id, version)`, partial unique `(status) WHERE status='PUBLISHED'`; `loyalty_program_scope(version_id FK, item_id | family_id)`; `loyalty_reward(id, version_id FK, code, name, points_cost > 0, effect CHECK IN ('FREE_UNIT','AMOUNT_OFF_UNIT'), amount, scope item_id | family_id NULL)`, `UNIQUE(version_id, code)`; trigger: no change to a PUBLISHED version or its children.
- `V12021__promotions_create_loyalty_account_and_ledger.sql`: `loyalty_account(customer_id PK, status CHECK IN ('ACTIVE','SUSPENDED'), suspended_reason, enrolled_at, enrolled_by_actor_kind, enrolled_by_actor_id, version)`; `loyalty_point_entry(id, customer_id, kind CHECK IN ('EARN','REDEEM','EARN_REVERSAL','REDEEM_REVERSAL','ADJUSTMENT','EXPIRY'), points integer ≠ 0 with sign CHECK per kind, source_type CHECK IN ('ORDER','ORDER_LINE','ADJUSTMENT','EXPIRY'), source_id, reverses_entry_id, program_version_id, store_id, business_date, reason, actor_kind, actor_id, occurred_at)`, `UNIQUE(source_type, source_id, kind)`, index `(customer_id, occurred_at) INCLUDE (points)`, immutable trigger; `loyalty_allocation(debit_entry_id FK, earn_entry_id FK, points > 0)`, PK both, deferred triggers Σ per debit = |debit points| and Σ per earning ≤ its points.

**`promotions.LoyaltyApi`** (root):
- `RedemptionResult redeem(RedeemCommand cmd)` (`MANDATORY`): `RedeemCommand(UUID customerId, String rewardCode, UUID sourceLineId, UUID orderId, UUID storeId, LocalDate businessDate, UUID itemId, UUID familyId, Money currentUnitPrice)`; `loyalty_account FOR UPDATE` (D132), checks per D112, inserts REDEEM + allocations (FIFO). Returns `RedemptionResult(UUID entryId, int points, Money newUnitPrice)`. Errors: 422 `LOYALTY_NOT_MEMBER`, `LOYALTY_ACCOUNT_SUSPENDED`, `LOYALTY_INSUFFICIENT_POINTS`, `LOYALTY_REWARD_NOT_APPLICABLE`.
- `void reverseRedemption(UUID sourceLineId, UUID voidId)` (`MANDATORY`): REDEEM_REVERSAL once; no-op when the line has no redemption.
- `Optional<EarnResult> earnForOrder(EarnCommand cmd)` (`MANDATORY`): `EarnCommand(UUID orderId, UUID storeId, UUID customerId, LocalDate businessDate, Instant at, List<EarnLine> lines)`, `EarnLine(UUID itemId, UUID familyId, Money gross, boolean rewarded)`. Empty when not a member, no published program, or 0 points. Idempotent by `UNIQUE`.
- `LoyaltyBalance balance(UUID customerId)` and `boolean isMember(UUID customerId)` (readOnly).

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/backoffice/loyalty/program-versions`, `PUT …/{id}`, `POST …/{id}/publish`, rewards CRUD | `LOYALTY_PROGRAM_MANAGE` | yes | 409 publishing a non-DRAFT |
| `POST /api/v1/pos/customers/{id}/loyalty` (enroll), `POST /api/v1/backoffice/loyalty/accounts/{customerId}/suspend` / `/resume {reason}` | `LOYALTY_ENROLL` | yes | 422 `CUSTOMER_INACTIVE`; 409 already enrolled |
| `GET /api/v1/pos/customers/{id}/loyalty`, `GET /api/v1/backoffice/loyalty/accounts/{customerId}/statement?from&to` | `LOYALTY_VIEW` | — | Balance = Σ; running balance per line |
| `POST /api/v1/backoffice/loyalty/accounts/{customerId}/adjustments {points, reason}` | `LOYALTY_ADJUST` | yes | 422 `REASON_REQUIRED`; a negative adjustment beyond the balance → 422 `LOYALTY_INSUFFICIENT_POINTS` |

**Tests:** `LoyaltyRedeemRaceTest`, `LoyaltyLedgerAllocationTest` (FIFO, deferred triggers fire on a bad manual insert), `LoyaltyEarnRuleTest` (floor, scope, rewarded lines excluded), `LoyaltyEarnIdempotencyTest`, `LoyaltyProgramPublishTest`, `LoyaltyNoBalanceColumnTest` (schema check).

**Acceptance:** the ledger half of A42.

### S41 — Reservations (dining)
**Write scope:** `dining/`, `db/migration/dining/V4005+`.

**Migrations:**
- `V4005__dining_create_reservation.sql`: `reservation(id, store_id, starts_at timestamptz, duration_minutes NULL > 0, party_size > 0, contact_name NOT NULL, contact_phone_digits CHECK '^[0-9]{4,20}$' NULL, customer_id NULL, notes, status CHECK IN ('BOOKED','SEATED','CANCELLED','NO_SHOW'), occupation_id, seated_at, cancelled_at, cancel_reason, no_show_at, no_show_by_actor_kind, created_by_actor_kind, created_by_actor_id, created_at, version)`, CHECK SEATED ⇔ `occupation_id` and `seated_at`, CHECK CANCELLED ⇒ reason; index `(store_id, starts_at) WHERE status = 'BOOKED'`; `reservation_table(reservation_id FK, table_id)` PK both, index `(table_id)`.
- `V4006__dining_create_dining_settings.sql`: `dining_settings(store_id PK, reservation_window_minutes DEFAULT 120, no_show_grace_minutes DEFAULT 30, version)`.

**Contracts:**
- `DiningApi.openOccupation(UUID tableId, UUID orderId, int guests, UUID employeeId, UUID reservationId)` (`MANDATORY`): the R2 method plus, when `reservationId` ≠ null, a CAS BOOKED → SEATED of a reservation of the same store linked to the new occupation (409 `RESERVATION_NOT_BOOKED`, 422 `RESERVATION_OTHER_STORE`). The R2 signature delegates with null.
- `TableState` and the map gain `reserved` and `nextReservationAt` (D126).
- `ReservationNoShowJob` (scheduled, D46): BOOKED with `starts_at + grace ≤ now` → NO_SHOW as SYSTEM, audited, `FOR UPDATE SKIP LOCKED`.

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET /api/v1/pos/reservations?date`; `GET /api/v1/backoffice/reservations?storeId&from&to` | `RESERVATION_VIEW` | — | Span ≤ 92 days (422 `RANGE_TOO_LARGE`) |
| `POST /api/v1/pos/reservations {startsAt, durationMinutes?, partySize, contactName, contactPhone?, customerId?, notes?, tableIds[]}` | `RESERVATION_MANAGE` | yes | 422 `TABLE_OTHER_STORE`, `CUSTOMER_NOT_FOUND`; no overlap check (§4) |
| `PUT /api/v1/pos/reservations/{id} {version, …}` | `RESERVATION_MANAGE` | — | 409 stale or not BOOKED |
| `POST /api/v1/pos/reservations/{id}/cancel {version, reason}`; `/no-show {version}` | `RESERVATION_MANAGE` | yes | 422 `RESERVATION_NOT_STARTED` for a no-show before the start |
| `PUT /api/v1/backoffice/dining/settings/{storeId}` | `DINING_MANAGE` | — | |

**Tests:** `ReservationLifecycleTest` (all states × commands), `ReservationSeatRaceTest`, `ReservationIndicatorTest` (window, independent of lock and occupation, store zone), `ReservationNoShowJobTest` (fixed `Clock`), `ReservationNeverBlocksTest` (a BOOKED table opens for a walk-in).

**Acceptance:** A50 except the seat through orders (S50).

### S42 — Inventory core: stock items, warehouses, ledger, balance, manual movements, transfers
**Write scope:** `inventory/` except `inventory/recipe/**`, `inventory/consumption/**`, `inventory/preparation/**`, `inventory/purchasing/**`, `inventory/count/**`; `db/migration/inventory/V13001–V13019`.

**Migrations:**
- `V13001__inventory_create_units_and_stock_items.sql`: `CREATE SCHEMA inventory`; `stock_unit(code PK, dimension CHECK IN ('MASS','VOLUME','COUNT'), factor_to_base NUMERIC(19,9) > 0)` seeded g, kg, ml, l, un; `stock_item(id, code UNIQUE, name, base_unit FK, kind CHECK IN ('PURCHASED','PREPARED'), active, version)`; `stock_item_pack(id, stock_item_id FK, code, name, quantity_base > 0)`, `UNIQUE(stock_item_id, code)`.
- `V13002__inventory_create_warehouse.sql`: `warehouse(id, store_id, code, name, negative_policy CHECK IN ('ALLOW','REFUSE_MANUAL') DEFAULT 'ALLOW', active, version)`, `UNIQUE(store_id, code)`; `consumption_route(id, store_id, destination_code NULL, warehouse_id FK)` with unique `(store_id, coalesce(destination_code, ''))`; `stock_level(warehouse_id, stock_item_id, min_quantity ≥ 0, version)` PK both; `inventory_activation(store_id PK, active_from, activated_by_account_id)`.
- `V13003__inventory_create_stock_movement.sql`: `stock_movement(id, posting_seq BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE, warehouse_id, store_id, stock_item_id, type CHECK IN ('OPENING','RECEIPT','SUPPLIER_RETURN','SALE_CONSUMPTION','CONSUMPTION_REVERSAL','WASTE','RETURN_TO_STOCK','PREPARATION_OUT','PREPARATION_IN','TRANSFER_OUT','TRANSFER_IN','TRANSFER_SHORTAGE','COUNT_ADJUSTMENT','MANUAL_ADJUSTMENT','REVALUATION'), quantity NUMERIC(19,6), unit_cost NUMERIC(19,6) ≥ 0, value NUMERIC(19,4), currency, source_type, source_id, source_line_id, reverses_movement_id, recipe_version_id, policy_version_id, sale_item_id, business_date, occurred_at, posted_at, actor_kind, actor_id, reason)`; CHECK sign of quantity per type (REVALUATION ⇔ quantity 0); `UNIQUE(source_type, source_id, stock_item_id, type, warehouse_id)`; indexes `(warehouse_id, stock_item_id, posting_seq)`, `(store_id, business_date, type)`, `(stock_item_id, occurred_at)`; immutable trigger.
- `V13004__inventory_create_stock_balance.sql`: `stock_balance(warehouse_id, stock_item_id, quantity NUMERIC(19,6), value NUMERIC(19,4), average_cost NUMERIC(19,6), last_posting_seq, updated_at)` PK both.
- `V13005__inventory_create_stock_transfer.sql`: `stock_transfer(id, from_warehouse_id, to_warehouse_id, from_store_id, to_store_id, status CHECK IN ('DRAFT','SHIPPED','RECEIVED','CANCELLED'), shipped_at, received_at, …, version)`, CHECK different warehouses; `stock_transfer_line(transfer_id FK, stock_item_id, quantity_shipped > 0, quantity_received ≥ 0 ≤ shipped)`.
- `V13006__inventory_create_consumption_exception.sql` (the table S47 writes): `consumption_exception(id, store_id, source_type, source_id, item_id, kind CHECK IN ('NO_RECIPE','NO_ROUTE','INACTIVE_STOCK_ITEM','NOT_TRACKED_CHANGED'), created_at, resolved_at, resolution)`, `UNIQUE(source_type, source_id, kind)`.

**Contracts:**
- `inventory.service.StockLedger` (internal, the one posting path for S47–S49): `List<MovementView> post(List<PostMovement> movements)` (`MANDATORY`): locks `stock_balance` rows in `(warehouse_id, stock_item_id)` order (inserting missing rows with `ON CONFLICT DO NOTHING` first), values OUTs at the average, applies D113 (including REVALUATION when crossing zero), inserts with `ON CONFLICT DO NOTHING` on the unique key (a conflicting row is returned, not reposted), applies `negative_policy` for `manual = true` postings (422 `NEGATIVE_STOCK_REFUSED`), and publishes `StockMovementsPosted` once per source.
- `inventory.InventoryFacts` (root, readOnly): `Optional<MovementSet> movementsOf(String sourceType, UUID sourceId)`; `Page<SourceRef> sourceIds(UUID storeId, LocalDate from, LocalDate to, Pageable p)`.
- Publishes the S37 event `StockMovementsPosted` once per source and transaction.
- `StockBalanceReconciliationJob` (nightly, D46): Σ movements vs balance per row; mismatch → ERROR log with ids and gauge `inventory_balance_drift`.

**Endpoints** (BackOffice, `/api/v1/backoffice/inventory`):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `/stock-items` (+ `/packs`), `/warehouses`, `/consumption-routes`, `/stock-levels` (CRUD) | `INVENTORY_MANAGE` (`INVENTORY_VIEW` for GET) | POST | 422 `UNIT_DIMENSION_MISMATCH`, `WAREHOUSE_STORE_MISMATCH` |
| `POST /activations {storeId, activeFrom}` | `INVENTORY_MANAGE` | yes | 409 already active |
| `GET /stock?warehouseId&belowMinimum&negative`; `GET /movements?warehouseId&stockItemId&from&to&type` | `INVENTORY_VIEW` | — | Paged (D23) |
| `POST /movements/waste`, `/manual-adjustments`, `/returns-to-stock {warehouseId, lines[{stockItemId, quantity, unit}], reason}` | `INVENTORY_MOVE` | yes | 422 `REASON_REQUIRED`, `NEGATIVE_STOCK_REFUSED`; MANUAL_ADJUSTMENT IN needs a unit cost |
| `POST /transfers`, `PUT /transfers/{id}`, `POST /transfers/{id}/ship`, `/receive {lines[{stockItemId, quantityReceived}]}`, `/cancel` | `INVENTORY_TRANSFER` at the source (ship) and destination (receive) store | yes | 409 `TRANSFER_STATE`; 422 `SAME_WAREHOUSE`, `RECEIVED_EXCEEDS_SHIPPED`; same store → ship receives |
| `POST /balances/rebuild {warehouseId?}` | `INVENTORY_REBUILD` | yes | Replays in `posting_seq` order; audited |
| `GET /status?storeId` | `INVENTORY_VIEW` | — | Activation, `pendingFacts`, open exceptions, negatives, drift |
| `GET /reports/valuation`, `/below-minimum`, `/waste`, `/rotation?from&to` | `INVENTORY_VIEW` | — | D131; CSV with `REPORT_EXPORT` (audited) |

**Tests:** `MovingAverageTest` (unit: receipts 10.00 and 12.00 → 11.00; OUT valuation; crossing zero with REVALUATION; scale and HALF_UP), `AverageCostConcurrencyTest`, `StockPostingDeadlockTest`, `StockMovementImmutableTest`, `NegativePolicyTest`, `TransferBetweenStoresTest` (shortage, permissions per store), `StockBalanceReconcileTest` (drift detected; rebuild restores), `UnitConversionTest`.

**Acceptance:** A48, A49, the valuation half of A52.

### S43 — Fiscal R4: discounts on document lines
**Write scope:** `fiscal/`, `db/migration/fiscal/V10011+`.

**Migrations:** `V10011__fiscal_document_line_discount.sql`: `fiscal_document_line.list_unit_price NUMERIC(19,4) NULL`, `discount_label VARCHAR(80) NULL`, CHECK `list_unit_price IS NULL OR list_unit_price >= unit_price`. Added NULL, so no ISSUED row is updated.

**Contracts:** `DraftLine` gains `listUnitPrice` and `discountLabel` as its last components (an overload keeps the old ones). The D59 and bounded checks use `unitPrice` as before. The `internal` adapter prints, under a discounted line, `  Desconto (<label>): −<list gross − gross>` and `  Oferta` for a 0.00 line. `DocumentFacts` lines gain `listUnitPrice` and `discountLabel`.

**Tests:** `DiscountedDraftLineTest` (amounts check unchanged, list ≥ unit enforced), `InternalAdapterDiscountPrintoutTest`, `IssuedImmutableTest` still green.

**Acceptance:** the document half of A40.

### S44 — Deploy: two-installation isolation drill (§14 #1)
**Write scope:** `deploy/drills/isolation/**`, `deploy/test-two-installations.sh`.

**Deliverable:** `deploy/test-two-installations.sh` brings up Compose projects `rp-a` and `rp-b` from separate env files (`deploy/drills/isolation/a.env`, `b.env`), with their own volumes, networks, secret directories and recipients (throwaway keys, production mode, D98). It asserts, failing on the first miss:
1. `docker volume ls` and `docker network ls` show no shared volume or network; no secret path is mounted in both.
2. A's admin login and A's session cookie get 401 on B (and the reverse).
3. B's backend container cannot resolve or connect to A's database host (`getent` and a TCP probe fail).
4. Back up A, change data in A, restore A: A matches its backup and B's `pg_dump` checksum is unchanged.
5. It tears both stacks down and removes their volumes.
It writes `drill-isolation-<UTC>.json`.

**Tests:** the script itself, run by `deploy/test-two-installations.sh --self-test` (shellcheck-clean, fails when a step is forced to fail with `ISOLATION_FAULT=<step>`).

**Acceptance:** A55.

### S45 — Load smoke: 10 terminals (§12)
**Write scope:** `deploy/load/**`.

**Deliverable:** `deploy/load/LoadSmoke.java` (a single-file program, JDK `HttpClient` and virtual threads, no dependency) and `deploy/load/load-smoke.sh`. Against a running stack seeded by `--seed` (one store, 10 enrolled terminals, 10 employees, 40 tables, a published catalog of 300 items), it runs 10 concurrent terminal loops for a fixed duration: open table, two rounds, read the order, subtotal, partition, pay cash, close; plus map and catalog reads. It reports p50/p95/p99 per endpoint and fails when a local simple command or read has p95 ≥ 500 ms (§12; printing and external calls excluded). JSON report `load-<UTC>.json`. The seed and the random choices use a fixed seed, so runs are comparable.

**Tests:** `load-smoke.sh --dry-run` validates the plan against `/v3/api-docs` (every endpoint it calls exists).

**Acceptance:** A56 on the R3 paths.

---

## Group 2 (after group 1): S46 ∥ S47 ∥ S48 ∥ S49
S46 needs S38, S39, S40 merged. S47, S48 and S49 need S42 merged.

### S46 — Orders R4: pricing, menus, discounts, loyalty, customer
**Write scope:** `orders/` except `orders/delivery/**`; `db/migration/orders/V6009–V6019`.

**Migrations:**
- `V6009__orders_line_pricing_snapshot.sql`: on `order_line`: `kind VARCHAR(10) NOT NULL DEFAULT 'STANDARD' CHECK IN ('STANDARD','MENU','FEE')`, `automatic BOOLEAN NOT NULL DEFAULT false`, `list_unit_price NUMERIC(19,4)`, `promotion_id`, `promotion_code`, `promotion_name`, `promotion_release_id`, `promotion_version`; CHECK `list_unit_price IS NULL OR unit_price <= list_unit_price`. The confirmed-line trigger keeps refusing UPDATE (the columns are set at confirmation only).
- `V6010__orders_create_line_component.sql`: `order_line_component(id, order_id, line_id, group_code, item_id, variant_id, item_code, description, quantity > 0, destination, position)`, FK `(line_id, order_id)`; trigger: immutable once its line is CONFIRMED.
- `V6011__orders_create_price_adjustment_and_split.sql`: `order_line_price_adjustment(id, order_id, line_id, seq, kind CHECK IN ('DISCOUNT','OFFER','REWARD','REVERSAL'), percent, previous_unit_price, unit_price ≥ 0, currency, reward_code, loyalty_entry_id, reason NOT NULL, operator_employee_id, approver_employee_id, at)`, `UNIQUE(line_id, seq)`, CHECK direction per kind, CHECK OFFER ⇒ `unit_price = 0` and approver; `order_line_split(id, order_id, source_line_id, target_line_id UNIQUE, quantity > 0, adjustment_id, at, employee_id)`; `order_line.origin_split_id`; the origin CHECK becomes "exactly one of `batch_id`, `origin_transfer_id`, `origin_split_id`"; immutable triggers.
- `V6012__orders_order_customer.sql`: `order_header.customer_id UUID NULL`, index `(customer_id) WHERE customer_id IS NOT NULL`.

**Contracts:**
- **Confirmation** (D101–D104): per draft, catalog price (D58) → `PromotionsApi.price` with the confirmation instant, the order mode, `member = LoyaltyApi.isMember(customerId)` → snapshot `list_unit_price`, `unit_price` (effective) and the promotion fields. `ConfirmBatchRequest` gains `promotionVersion` and `expectedGross` (optional, D104): 409 `PROMOTIONS_CHANGED`, 409 `PRICES_CHANGED {lines[]}`. Sold-out (`CatalogApi.soldOut`) → 422 `ITEM_SOLD_OUT`. A MENU draft's components → validated (422 `MENU_SELECTION_INVALID`) and frozen. Draft previews use the same evaluation (`preview = true`).
- **Drafts:** `AddLineRequest` gains `components[{groupCode, itemId, variantId}]`. `POST /orders/{id}/lines/macro {macroCode}` adds the macro's drafts. Opening a table adds `TABLE_OPEN_PER_GUEST` automatic drafts (quantity × guests); opening any order adds the matching `ORDER_OPEN` items.
- **Adjustments** (D105, D106): `POST /orders/{id}/discounts` with lock NONE. `OrdersApi.adjustLinePrice(SettledAdjustmentCommand cmd, UUID approverEmployeeId)` (`MANDATORY`, for settlement): the caller holds `order_settlement` and checked headroom; it ignores the PAYMENT lock and refuses INVOICE (422 `ORDER_FROZEN`). `SettledAdjustmentCommand(UUID orderId, long expectedVersion, List<AdjustLine> lines, AdjustmentKind kind, BigDecimal percent, Money unitPrice, String rewardCode, String reason, UUID operatorEmployeeId)`. A partial quantity splits first. REWARD calls `LoyaltyApi.redeem`; a void of a rewarded line calls `reverseRedemption` (both routes). Publishes `LineDiscountsRecorded(orderId, storeId, sourceType BATCH|ADJUSTMENT, sourceId)` (also after a round with any promoted line).
- **Customer and loyalty** (D111, D130): `POST /orders/{id}/customer {orderVersion, customerId}`; 422 `LOYALTY_REDEEMED` when changing after a redemption. `OrdersApi.close` calls `LoyaltyApi.earnForOrder` before committing the close when `customer_id` is set.
- **Read contracts:** `BillingLine` gains `listUnitPrice`, `discountLabel` (`unitPrice` is the effective price); `OrderBillingView` gains `customerId` (and `delivery = null` until S50). `BatchFacts` fills `components`. `VoidFacts.consumedLineId` follows splits. `DiscountFacts discountFacts(String sourceType, UUID sourceId)` (readOnly): lines with list and effective unit price, quantity, discount gross, origin (`PROMOTION`, `MANUAL`, `OFFER`, `LOYALTY`), promotion, operator, approver, business day. `Page<SourceRef> discountSourceIds(storeId, from, to, p)`.
- **Events:** `OrderBatchConfirmed` gains `mode`; `Line` gains `components` (`List<ComponentLine(itemCode, description, quantity, destination)>`), both last, nullable in old publications.

**Endpoints** (POS):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/orders/{id}/batches {orderVersion, catalogVersion, promotionVersion?, expectedGross?}` | `ORDER_EDIT` | yes | + 409 `PROMOTIONS_CHANGED`, `PRICES_CHANGED`; 422 `ITEM_SOLD_OUT`, `MENU_SELECTION_INVALID` |
| `POST /api/v1/pos/orders/{id}/lines/macro {orderVersion, macroCode}` | `ORDER_EDIT` | yes | 422 `MACRO_NOT_PUBLISHED` |
| `POST /api/v1/pos/orders/{id}/discounts {orderVersion, lines[{lineId, quantity?}], kind, percent?, unitPrice?, rewardCode?, reason}` + Approver when D106 needs one | `ORDER_DISCOUNT`, `ORDER_OFFER` or `LOYALTY_REDEEM` by kind; approver `ORDER_DISCOUNT_APPROVE` | yes | 422 `ORDER_IN_SETTLEMENT`, `DISCOUNT_LIMIT_EXCEEDED`, `PRICE_INCREASE_NOT_ALLOWED`, `SELF_APPROVAL`, `LOYALTY_CUSTOMER_REQUIRED`, `REWARD_NEEDS_SINGLE_UNIT`, `QUANTITY_EXCEEDS_NET` |
| `POST /api/v1/pos/orders/{id}/customer {orderVersion, customerId}` | `ORDER_EDIT` | yes | 422 `CUSTOMER_INACTIVE`, `LOYALTY_REDEEMED` |
| `GET /api/v1/pos/orders/{id}` | unchanged | — | Lines show list price, effective price, discount, origin, components, adjustments |

**Tests:** `ConfirmWithPromotionTest`, `HappyHourBoundaryTest`, `PromotionRepublishTest`, `PromotionSnapshotTest` (§14 #7), `MenuConfirmationTest`, `SoldOutAtConfirmationTest`, `MacroAndAutomaticDraftsTest`, `DiscountLimitTest`, `DiscountConcurrencyTest`, `LineSplitTest` (net quantity, no second ticket, transfer of a split line), `RewardRedemptionTest` (split one unit, void reverses), `LoyaltyEarnAtCloseTest` (with a settlement close in the IT), `R3LineReadsAsListPriceTest`, all R2/R3 order tests green.

**Acceptance:** A38, A39, A40 (orders route), A42 (orders half), A43 (orders half).

### S47 — Inventory: recipes, consumption, preparation
**Write scope:** `inventory/recipe/**`, `inventory/consumption/**`, `inventory/preparation/**`; `db/migration/inventory/V13020–V13039`.

**Migrations:**
- `V13020__inventory_create_recipe.sql`: `recipe(id, item_id, variant_id, output_stock_item_id, name)`, CHECK sale recipe (`item_id`) XOR preparation recipe (`output_stock_item_id`), unique `(item_id, coalesce(variant_id, zero-uuid)) WHERE item_id IS NOT NULL` and `(output_stock_item_id)`; `recipe_version(id, recipe_id FK, version_no, status CHECK IN ('DRAFT','ACTIVE','SUPERSEDED'), yield_quantity > 0, yield_unit, effective_from, effective_to, notes, created_by_account_id, activated_by_account_id, version)`, `UNIQUE(recipe_id, version_no)`, partial unique `(recipe_id) WHERE status='ACTIVE'`, an exclusion of overlapping `[effective_from, effective_to)` per recipe (`btree_gist` is not needed: checked under `recipe FOR UPDATE` at activation, plus a trigger); `recipe_ingredient(id, recipe_version_id FK, stock_item_id, quantity > 0, unit, kind CHECK IN ('INGREDIENT','PACKAGING'), modes text[] NULL)`; trigger: ingredients and yield immutable unless the version is DRAFT.
- `V13021__inventory_create_consumption_policy.sql`: `consumption_policy_version(id, version_no UNIQUE, status CHECK IN ('DRAFT','ACTIVE','SUPERSEDED'), effective_from, effective_to)`, `consumption_policy_rule(policy_version_id FK, family_id NULL = default, trigger CHECK IN ('ROUND_CONFIRMED'), tracked BOOLEAN)`, unique `(policy_version_id, coalesce(family_id, zero-uuid))`. Seed: version 1, default rule tracked.
- `V13022__inventory_create_preparation.sql`: `preparation(id, store_id, warehouse_id, recipe_version_id, output_quantity > 0, status CHECK IN ('DRAFT','CONFIRMED','CANCELLED'), confirmed_at, actor…, version)`.

**Contracts:**
- `inventory.consumption.RoundConsumptionListener` (`@ApplicationModuleListener` on `OrderBatchConfirmed`): pulls `OrdersApi.batchFacts`; skips lines confirmed before the store's `active_from` (D122); per line or component resolves policy, recipe version at `confirmedAt` (variant, then item), packaging by mode, warehouse by route (destination, then default); posts `SALE_CONSUMPTION` through `StockLedger.post` with source `ORDER_LINE` / `ORDER_LINE_COMPONENT`, `sale_item_id`, `business_date` = the order's business day, `occurred_at = confirmedAt`; unresolved → `ConsumptionException`. A menu line consumes only its components. Quantity per ingredient = line qty × component qty × ingredient qty ÷ yield, converted to the base unit, HALF_UP scale 6.
- `inventory.consumption.VoidConsumptionListener` (on `OrderLinesVoided`): pulls `VoidFacts`; finds the movements of `consumedLineId` (and its components); posts `CONSUMPTION_REVERSAL` (source `VOID`, `voidId`) at their unit cost, capped at the unreversed quantity, and for `wasted = true` also `WASTE` (D117).
- Preparation confirm: ingredient `PREPARATION_OUT` at average and one `PREPARATION_IN` valued at their Σ, one transaction (D121).

**Endpoints** (`/api/v1/backoffice/inventory`):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `/recipes` (CRUD), `POST /recipes/{id}/versions`, `PUT /recipes/{id}/versions/{v}`, `POST …/{v}/activate {effectiveFrom?}` | `RECIPE_MANAGE` | yes | 409 `RECIPE_VERSION_IMMUTABLE`; 422 `RECIPE_CYCLE`, `UNIT_DIMENSION_MISMATCH`, `ITEM_NOT_FOUND` (`CatalogApi.items`) |
| `/consumption-policies` (versions, rules, activate) | `RECIPE_MANAGE` | yes | |
| `GET /consumption-exceptions?storeId&open`; `POST /consumption-exceptions/{id}/reprocess` | `INVENTORY_VIEW`; `INVENTORY_REBUILD` | — ; yes | Reprocess uses the version effective at confirmation, else the first version activated after it (flagged `late_recipe`) |
| `POST /preparations`, `POST /preparations/{id}/confirm`, `/cancel` | `INVENTORY_MOVE` | yes | 422 `NEGATIVE_STOCK_REFUSED` under `REFUSE_MANUAL` |

**Tests:** `ConsumptionIdempotencyTest` (redelivery and restart, second context on the same DB), `RecipeVersionAtConfirmationTest`, `ConsumptionRouteTest` (bar destination → bar warehouse; default), `PackagingByModeTest`, `VoidReversalTest` (wasted and not; partial voids capped), `SplitLineVoidConsumptionTest` (after S46 merges, otherwise with a transferred line), `GoLiveCutoffTest`, `ConsumptionExceptionTest`, `PreparationCostTest`, `SaleNotBlockedByInventoryTest` (listener held by a latch: the round, payment and close commit).

**Acceptance:** A44, A45.

### S48 — Inventory: purchasing
**Write scope:** `inventory/purchasing/**`; `db/migration/inventory/V13040–V13059`.

**Migrations:**
- `V13040__inventory_create_supplier.sql`: `supplier(id, code UNIQUE, name, tax_id, email, phone, notes, active, version)`; `supplier_item(id, supplier_id FK, stock_item_id, pack_id NULL, supplier_code, last_unit_cost NUMERIC(19,6), last_cost_at, version)`, unique `(supplier_id, stock_item_id, coalesce(pack_id, zero-uuid))`.
- `V13041__inventory_create_purchase_order.sql`: `purchase_order(id, store_id, warehouse_id, supplier_id, number, status CHECK IN ('DRAFT','SUBMITTED','CLOSED','CANCELLED'), expected_at, notes, submitted_at, closed_at, …, version)`, `UNIQUE(store_id, number)`; `purchase_order_line(id, purchase_order_id FK, stock_item_id, pack_id, quantity > 0, unit_cost ≥ 0)`; trigger: lines immutable once SUBMITTED.
- `V13042__inventory_create_goods_receipt.sql`: `goods_receipt(id, store_id, warehouse_id, supplier_id, purchase_order_id NULL, supplier_document_ref, status CHECK IN ('DRAFT','CONFIRMED','CANCELLED'), received_at, confirmed_at, confirmed_by_account_id, version)`; `goods_receipt_line(id, receipt_id FK, purchase_order_line_id NULL, stock_item_id, pack_id, pack_quantity > 0, quantity_base > 0, unit_cost_base NUMERIC(19,6) ≥ 0)`; index `(purchase_order_line_id)`; trigger: lines immutable once CONFIRMED.
- `V13043__inventory_create_supplier_return_and_corrections.sql`: `supplier_return(…, goods_receipt_id NULL, status DRAFT/CONFIRMED/CANCELLED)`, `supplier_return_line(…, receipt_line_id NULL, quantity_base > 0)`; `receipt_cost_correction(id, receipt_line_id, previous_unit_cost, new_unit_cost, reason NOT NULL, revalued_quantity, variance_value, actor…, at)` append-only; `cost_variance(id, store_id, stock_item_id, business_date, value, source correction id)` append-only.

**Contracts:**
- Receipt confirm (one transaction, CAS DRAFT → CONFIRMED): `RECEIPT` movements (source `GOODS_RECEIPT`, line id) through `StockLedger.post`; `supplier_item.last_unit_cost` updated; receipt progress of the purchase order derived from Σ confirmed lines per order line.
- Return confirm: `SUPPLIER_RETURN` at the average (D113); when linked, the receipt line's cost is kept for reference, `(average − receipt cost) × q` is booked as that line's `cost_variance`, and the quantity is ≤ received − returned (422 `RETURN_EXCEEDS_RECEIVED`); `manual = true` for the negative policy (D125 amendment).
- Cost correction: D125 (REVALUATION for the on-hand share; the rest to `cost_variance`), publishes `StockMovementsPosted` with source `COST_CORRECTION`.

**Endpoints** (`/api/v1/backoffice/inventory`):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `/suppliers` (CRUD), `/suppliers/{id}/items` | `PURCHASE_MANAGE` | POST | |
| `/purchase-orders` (CRUD while DRAFT), `POST /{id}/submit`, `/close`, `/cancel` | `PURCHASE_MANAGE` | yes | 409 `PO_STATE`; 422 `PO_HAS_RECEIPTS` on cancel |
| `GET /purchase-orders/{id}` | `INVENTORY_VIEW` | — | Lines with ordered, received, outstanding and progress |
| `/receipts` (CRUD while DRAFT), `POST /receipts/{id}/confirm`, `/cancel` | `GOODS_RECEIVE` | yes | 409 `RECEIPT_STATE`; 422 `WAREHOUSE_STORE_MISMATCH`, `PO_NOT_SUBMITTED`; response `warnings: [OVER_RECEIPT]` |
| `/supplier-returns` (same shape) | `GOODS_RECEIVE` | yes | 422 `RETURN_EXCEEDS_RECEIVED`, `NEGATIVE_STOCK_REFUSED` |
| `POST /receipts/{id}/lines/{lineId}/cost-corrections {newUnitCost, reason}` | `GOODS_RECEIVE` | yes | 422 `RECEIPT_NOT_CONFIRMED` |
| `GET /reports/purchases?from&to&groupBy=supplier\|item` | `INVENTORY_VIEW` | — | |

**Tests:** `ReceiptConfirmOnceTest`, `PartialReceiptProgressTest`, `PurchaseOrderNeverMovesStockTest`, `SupplierReturnCostTest`, `CostCorrectionTest` (on-hand share revalued, rest to variance), `LastCostUpdateTest`, `PackConversionTest`.

**Acceptance:** A46.

### S49 — Inventory: counts and opening
**Write scope:** `inventory/count/**`; `db/migration/inventory/V13060–V13069`.

**Migrations:** `V13060__inventory_create_stock_count.sql`: `stock_count(id, store_id, warehouse_id, kind CHECK IN ('OPENING','REGULAR'), scope CHECK IN ('ALL','LIST'), status CHECK IN ('DRAFT','IN_PROGRESS','SUBMITTED','APPROVED','REJECTED','CANCELLED'), started_at, submitted_at, decided_at, decided_by_account_id, reject_reason, version)`, partial unique `(warehouse_id) WHERE status IN ('DRAFT','IN_PROGRESS','SUBMITTED')`; `stock_count_line(id, count_id FK, stock_item_id, counted_quantity ≥ 0, unit, counted_at, counted_by_actor…, unit_cost NULL (OPENING only), expected_quantity NULL, difference NULL)`, `UNIQUE(count_id, stock_item_id)`; trigger: lines immutable once SUBMITTED.

**Contracts:** approve (one transaction): 409 `CONSUMPTION_BACKLOG` while `EventPublicationStats.pendingFor("com.xavier.backend.inventory.consumption") > 0` (any incomplete consumption publication; the count is installation-wide, which is conservative and keeps the check a single indexed count); expected per line = Σ quantity with `occurred_at ≤ counted_at`; posts `COUNT_ADJUSTMENT` (or `OPENING` with the entered unit cost) with `occurred_at = counted_at`, source `STOCK_COUNT` line; stores expected and difference on the lines. Only one OPENING count per warehouse, and only before any other movement there (422 `OPENING_NOT_FIRST`).

**Endpoints** (`/api/v1/backoffice/inventory/counts`):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /counts {warehouseId, kind, scope, stockItemIds?}`, `POST /counts/{id}/start` | `INVENTORY_COUNT` | yes | 409 a live count exists |
| `PUT /counts/{id}/lines/{stockItemId} {countedQuantity, unit, countedAt?, unitCost?}` | `INVENTORY_COUNT` | — | `countedAt` defaults to now; 409 after submit |
| `POST /counts/{id}/submit`; `/approve`; `/reject {reason}`; `/cancel` | `INVENTORY_COUNT`; `INVENTORY_COUNT_APPROVE` for approve and reject | yes | 409 `COUNT_STATE`, `CONSUMPTION_BACKLOG` |
| `GET /reports/count-divergences?from&to` | `INVENTORY_VIEW` | — | |

**Tests:** `CountCutoffTest`, `CountApprovalOnceTest`, `CountBacklogTest` (listener held → 409; released → approve), `OpeningCountTest`, `RejectedCountKeptTest`.

**Acceptance:** A47.

---

## Group 3 (after S46; S50 also needs S41): S50 ∥ S53
S53 also needs S47 and S48.

### S50 — Orders R4: delivery and reservation seating
**Write scope:** `orders/delivery/**` and the orders files it must touch for the mode (`OrderMode`, open and close services, `OrderBillingView` construction), `db/migration/orders/V6020–V6029`.

**Migrations:**
- `V6020__orders_delivery_mode.sql`: `order_header.mode` CHECK adds `'DELIVERY'`; CHECK `mode <> 'DELIVERY' OR customer_id IS NOT NULL`.
- `V6021__orders_create_delivery.sql`: `delivery_zone(id, store_id, code, name, fee_item_id, fee_variant_id, active, version)`, `UNIQUE(store_id, code)`; `delivery(order_id PK FK, store_id, customer_id, phone_digits, address_line1 NOT NULL, address_line2, city, postal_code, address_notes, zone_id, zone_code, status CHECK IN ('PENDING','OUT_FOR_DELIVERY','DELIVERED','RETURNED','CANCELLED'), courier_employee_id, promised_at, dispatched_at, delivered_at, version)`, CHECK OUT_FOR_DELIVERY/DELIVERED ⇒ courier; index `(store_id, status)`; `delivery_event(id, order_id FK, from_status, to_status, at, actor_employee_id, courier_employee_id, reason)` append-only.

**Contracts:**
- `POST /pos/orders` accepts `mode: DELIVERY` with `delivery{customerId, addressId | address, zoneId, promisedAt?, notes?}` → order + `Delivery` PENDING + the zone's fee item as an automatic FEE draft (422 `FEE_ITEM_NOT_PUBLISHED`, `ZONE_INACTIVE`, `CUSTOMER_REQUIRED`).
- Transitions per D127, each with a `DeliveryEvent` and SSE `ORDER`. Dispatch requires no drafts (422 `DELIVERY_HAS_DRAFTS`), a confirmed round, and a courier assigned to the store (422 `COURIER_NOT_IN_STORE`, via `EmployeeDirectory`/assignments); publishes `DeliveryDispatched(orderId, deliveryEventId, storeId, terminalId)`.
- `OrdersApi.close` → 422 `DELIVERY_NOT_DELIVERED`; cancel of a DELIVERY order → delivery CANCELLED (from PENDING or RETURNED, else 422 `DELIVERY_IN_PROGRESS`).
- `OrderBillingView.delivery` = `DeliverySummary(status, courierEmployeeId, promisedAt, zoneCode)`.
- `OrdersProductionApi.deliverySlip(UUID orderId)` (readOnly): `DeliverySlip(orderNumber, customerName, phone, address, zone, promisedAt, lines[description, quantity], due)`.
- **Seating** (D126): `OpenTableRequest` gains `reservationId`; passed to `DiningApi.openOccupation`.

**Endpoints** (POS unless noted):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/orders {mode: DELIVERY, delivery{…}}` | `ORDER_EDIT` + `DELIVERY_OPERATE` | yes | as above |
| `PUT /api/v1/pos/deliveries/{orderId} {version, address?, zoneId?, promisedAt?}` | `DELIVERY_OPERATE` | — | 422 `ADDRESS_FROZEN` after dispatch |
| `POST /api/v1/pos/deliveries/{orderId}/dispatch {version, courierEmployeeId}`; `/delivered {version}`; `/returned {version, reason}` | `DELIVERY_OPERATE` | yes | 409 `DELIVERY_STATE` |
| `GET /api/v1/pos/deliveries?status`; `GET /api/v1/backoffice/deliveries?storeId&from&to&courierId` | `ORDER_VIEW` | — | With history |
| `/api/v1/backoffice/delivery-zones` (CRUD) | `DELIVERY_ZONE_MANAGE` | POST | |
| `POST /api/v1/pos/tables/{id}/open {…, reservationId?}` | unchanged | yes | 409 `RESERVATION_NOT_BOOKED` |

**Tests:** `DeliveryLifecycleTest` (states × commands), `DeliveryTransitionRaceTest`, `DeliveryFeeLineTest` (tax from the fee item), `DeliveryCloseGuardTest`, `DeliveryStatesIndependentTest` (lifecycle, financial state and delivery status), `SeatReservationOnOpenTest`.

**Acceptance:** A50 (seat), A51 (orders half).

### S53 — Reporting R4: cost of goods, margins, discounts
**Write scope:** `reporting/`, `db/migration/reporting/V14003+`.

**Migrations:**
- `V14003__reporting_create_consumption_and_discount_records.sql`: `consumption_record(movement_id PK, store_id, business_date, sale_item_id, family_code, warehouse_id, stock_item_id, type, quantity, value, source_type, source_id, projected_at)`, index `(store_id, business_date, sale_item_id)`; `discount_record(source_type, source_id, line_id, store_id, business_date, item_id, family_code, origin, kind, promotion_code, operator_employee_id, approver_employee_id, quantity, discount_gross, projected_at)`, PK `(source_type, source_id, line_id)`, index `(store_id, business_date, origin)`; `cost_variance_record(...)`.
- `V14004__reporting_sales_line_mode_delivery.sql`: `sales_record_line` mode CHECK adds `'DELIVERY'`.

**Contracts:** listeners in `com.xavier.backend.reporting.inventory` on `StockMovementsPosted` (pull `InventoryFacts.movementsOf`) and on `LineDiscountsRecorded` (pull `OrdersApi.discountFacts`); rebuild pulls `InventoryFacts.sourceIds` and `OrdersApi.discountSourceIds` (D84 path). `pendingFacts` counts the new listeners.

**Endpoints** (`/api/v1/backoffice/reports`):

| method & path | permission | idem | notes |
|---|---|---|---|
| `GET /margins?storeId&from&to&groupBy=item\|family\|day` | `REPORT_VIEW` | — | net sales, cost of sales, waste, variance, margin, margin % (D131); `asOf`, `pendingFacts`, `final` |
| `GET /discounts?storeId&from&to&groupBy=origin\|promotion\|employee\|approver\|item` | `REPORT_VIEW` | — | Includes cost of offers |
| CSV export of both | `REPORT_EXPORT` | — | Audited |

**Tests:** `MarginReportTest` (seeded day: net sales − consumption cost per item; waste apart), `DiscountReportTest`, `ReportingR4RebuildTest` (rebuild from empty → identical), `DeliveryModeProjectionTest`.

**Acceptance:** A52.

---

## Group 4 (after S50; S51 also needs S43): S51 ∥ S52

### S51 — Settlement R4
**Write scope:** `settlement/`, `db/migration/settlement/V9070+` (only if an index is needed).

**Contracts and rules:**
- `POST /api/v1/pos/orders/{id}/settlement/discounts` (D105): `order_settlement FOR UPDATE` → per line: no LIVE partition quantity (422 `QUANTITY_PARTITIONED`), not covered (422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`) → the ORDER residual after the discount ≥ ORDER-target paid + credit + open attempts (422 `DISCOUNT_EXCEEDS_UNPAID`) → `OrdersApi.adjustLinePrice`. Same permissions and approver rules as the orders route; `REVERSAL` refused (422 `PRICE_INCREASE_NOT_ALLOWED`).
- Draft lines carry `BillingLine.listUnitPrice` and `discountLabel` into `DraftLine` (residual and partition documents).
- `GET /pos/settlement/open-orders` and `OrderSettlementView` gain `mode`, `customerId` and `delivery`.
- The settled-void path keeps working for rewarded lines (orders reverses the redemption inside `voidLine`).

**Tests:** `SettledDiscountHeadroomTest`, `DiscountVersusPaymentRaceTest`, `SplitPreservesTotalsTest` extended with discounted and offered lines (A41), `DiscountedDocumentLineTest`, `OpenOrdersDeliveryTest`, all R3 settlement tests green.

**Acceptance:** A40 (settlement route), A41, A51 (settlement half).

### S52 — Production R4
**Write scope:** `production/`, `db/migration/production/V7007+`.

**Migrations:** `V7007__production_delivery_slip_source.sql`: the ticket `source_type` CHECK adds `'DELIVERY_SLIP'`.

**Contracts:** ticket rendering shows `ENTREGA #n` or `TAKEAWAY #n` from `OrderBatchConfirmed.mode` (R3 publications with null mode keep the R3 header); menu components are rendered under their line on **their own destination's** ticket (a component whose destination has no route is UNROUTED, D89). Listener on `DeliveryDispatched` → `OrdersProductionApi.deliverySlip` → a `DELIVERY_SLIP` ticket on the terminal's printer, deduped on `deliveryEventId`.

**Tests:** `MenuComponentRoutingTest` (kitchen and bar tickets for one menu), `DeliveryHeaderTest`, `DeliverySlipOnceTest` (redelivery prints once; a re-dispatch prints again), `R3PublicationCompatTest`.

**Acceptance:** A43 (production half), A51 (slip).

---

## S54 — Integrate (alone, after every slice has merged)

**Write scope:**
- `src/test/.../e2e`
- `docs/architecture/modules/` (`-Dmodulith.docs=true`)
- `CONTEXT.md`, `docs/architecture/backend-conventions.md`, `docs/adr/0028…0033` and the ADR README
- `deploy/load/**` (R4 paths in the load plan), `deploy/.env.example` (new properties), `deploy/README.md` (isolation and load sections)

**Deliverables:**
- **End-to-end journey** over HTTP with real security and `TcpFakePrinter`:
  1. Setup: store with kitchen and bar warehouses and routes, inventory activated with an opening count, stock items, recipes (one with packaging for takeaway), a menu, a happy hour, discount limits, a loyalty program with a reward, a member customer, a delivery zone with a fee item.
  2. Purchase order, two partial receipts at different costs, then a cost correction.
  3. A reserved table is seated; a round during the happy hour; a menu; a manual offer of one coffee (split, approver).
  4. A void not wasted and a void wasted; a reward redeemed; split, pay, close: points earned once.
  5. A delivery order: fee line, dispatch with slip, delivered, cash by the cashier, close.
  6. A count during service; a transfer to a second store with a shortage.
  7. Margins, discounts, stock valuation and the documents agree; a reporting rebuild and an inventory balance rebuild give identical results.
- **Restart matrix additions** (D90 pattern): (i) a crash after a round commit, before consumption → one set of movements; (j) a crash after a close with earning → one EARN; (k) a crash during a receipt confirm → one receipt.
- **A53** (verify and the new rules). **A55** (`deploy/test-two-installations.sh`) and **A56** (`deploy/load/load-smoke.sh` with the R4 paths) run against the R4 schema.
- ADRs 0028–0033 (D99, D101, D110, D113, D116, D127). `CONTEXT.md` adds: PromotionRelease ("publicação de promoções"), effective unit price ("preço efetivo"), price adjustment ("desconto/oferta"), line split, LoyaltyAccount and LoyaltyPointLedger ("movimentos de pontos"), reward ("prémio"), Menu and component ("menu"), macro, automatic item ("artigo automático"), sold-out ("esgotado"), daily dish ("prato do dia"), Reservation ("reserva"), Delivery and DeliveryZone ("entrega", "zona"), courier ("estafeta"), delivery slip, Warehouse ("armazém"), StockItem, StockMovement, StockBalance, average cost ("custo médio"), Recipe and RecipeVersion ("ficha técnica"), ConsumptionRoute, ConsumptionException, Preparation ("preparação"), StockCount ("inventário"), StockTransfer, Supplier, PurchaseOrder ("encomenda"), GoodsReceipt ("receção"), SupplierReturn ("devolução a fornecedor"), cost correction.
- **Acceptance:** A38–A56 end to end.

---

## Blocked-by graph and parallel groups

```
S37 Platform prefactor (alone)
 ├─► S38 Catalog R4 ─────────┐
 ├─► S39 Promotions ─────────┼─► S46 Orders pricing ──┬─► S50 Orders delivery ──┬─► S51 Settlement R4 (also S43) ─┐
 ├─► S40 Loyalty ────────────┘                        │   (also S41)            └─► S52 Production R4 ────────────┤
 ├─► S41 Reservations ───────────────────────────────────┘                                                         │
 ├─► S42 Inventory core ──┬─► S47 Recipes + consumption ─┬─► S53 Reporting R4 (also S46) ──────────────────────────┤
 │                        ├─► S48 Purchasing ────────────┘                                                         │
 │                        └─► S49 Counts ──────────────────────────────────────────────────────────────────────────┤
 ├─► S43 Fiscal R4 ───────────────────────────────────────────────────────────────────────────────────────────────┤
 ├─► S44 Isolation drill ──────────────────────────────────────────────────────────────────────────────────────────┤
 └─► S45 Load smoke ───────────────────────────────────────────────────────────────────────────────────────────────┴─► S54 Integrate
```

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S37 alone | `access/`, the two new package-infos, read-only additions in `orders/`, `catalog/`, `customers/`, package-infos, architecture tests |
| 1 | S38 ∥ S39 ∥ S40 ∥ S41 ∥ S42 ∥ S43 ∥ S44 ∥ S45 | `catalog/`+5003+; `promotions/` (no `loyalty/`)+12001–12019; `promotions/loyalty/**` and root `Loyalty*`+12020–12039; `dining/`+4005+; `inventory/` core+13001–13019; `fiscal/`+10011+; `deploy/drills/isolation/**`; `deploy/load/**` |
| 2 | S46 ∥ S47 ∥ S48 ∥ S49 | `orders/` (no `delivery/`)+6009–6019; `inventory/recipe/**`, `consumption/**`, `preparation/**`+13020–13039; `inventory/purchasing/**`+13040–13059; `inventory/count/**`+13060–13069. S47–S49 use `StockLedger` and never edit it |
| 3 | S50 ∥ S53 | `orders/delivery/**` and the mode files+6020–6029; `reporting/`+14003+ |
| 4 | S51 ∥ S52 | `settlement/`+9070+; `production/`+7007+ |
| 5 | S54 alone | tests, docs, `deploy/load/**`, `deploy/.env.example`, `deploy/README.md` |

S50 is serial after S46 because both write orders services (open, close, billing view). S51 waits for S50 because `OrderBillingView.delivery` is set there.

Rules, as in R1–R3:
- No `pom.xml` edits.
- Each slice has its own `rtk mvn test -Dtest=…` that can go red on its own (deploy slices: their script's `--self-test` or `--dry-run`).
- Slices code against the contracts above and never read another module's tables.
- Within a group, merge in ascending slice number, so Flyway versions apply in order on persisted dev databases.
- No slice is interactive.
- Each PR targets `main`; none is stacked.

## Acceptance criteria → slices

| criterion (r4-decisions §17) | slices |
|---|---|
| A38, A39 | S39, S46 |
| A40 | S43, S46, S51 |
| A41 | S51 |
| A42 | S40, S46 |
| A43 | S38, S46, S52 |
| A44, A45 | S37, S47 |
| A46 | S48 |
| A47 | S49 |
| A48, A49 | S42 |
| A50 | S41, S50 |
| A51 | S50, S51, S52 |
| A52 | S42, S53 |
| A53 | S37, S54 |
| A54 | S37 and every endpoint slice |
| A55 | S44, S54 |
| A56 | S45, S54 |
