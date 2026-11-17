package com.xavier.backend.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D169, D171 (S68): the hot inventory queries keep their index under row-level security, as the application role in
 * one store's scope (the policy is an InitPlan filter on top; every predicate the indexes serve is a leakproof
 * {@code uuid}, {@code date} or {@code text} comparison). Deterministic: a fixed seed of 20 stores with one warehouse
 * and 25 balances each and 10 days of movements, then {@code ANALYZE} (as the owner) and {@code EXPLAIN} of the
 * repository's own SQL in one transaction with {@code enable_seqscan = off}, asserting the index node and its
 * condition.
 *
 * <p>D169 audit of inventory's {@code numeric}/{@code jsonb}/{@code LIKE} predicates on protected tables: none drives
 * an index. The {@code quantity}/{@code value} comparisons ({@code < 0}, {@code <> 0}, below the minimum) filter rows
 * an index on {@code warehouse_id}/{@code store_id} already found; the one partial index on a numeric predicate,
 * {@code stock_balance_negative_ix (warehouse_id) WHERE quantity < 0}, is still used for the negative count.
 *
 * <p>R6 (D189, S74c): explained in tenant A with tenant B's identical rows present (same codes, as many stores), so
 * the restrictive {@code tenant_scope} policy is part of every plan: {@code tenant_id} leads the keys and the store
 * indexes, and the policy's InitPlan is an index condition.
 */
@InventoryModuleTest
class InventoryPlanTest {

    static final LocalDate DAY = LocalDate.parse("2026-10-01");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StoreScopes scopes;

    @Autowired
    TenantScopes tenants;

    UUID store;
    UUID warehouse;

    @BeforeEach
    void twentyStoresInEachOfTwoTenants() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        seed(TenantTestSupport.B, run);
        seed(TenantTestSupport.A, run);
        var first = OwnerDatabase.shared().jdbc()
                .sql("SELECT id, store_id FROM inventory.warehouse WHERE code = ? AND tenant_id = ? ORDER BY id LIMIT 1")
                .param("PLAN-" + run).param(TenantTestSupport.A).query().singleRow();
        warehouse = (UUID) first.get("id");
        store = (UUID) first.get("store_id");
    }

    private static void seed(UUID tenant, String run) {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        owner.sql("""
                INSERT INTO inventory.stock_item (tenant_id, id, code, name, base_unit, kind, active, version,
                    created_at, updated_at)
                SELECT :tenant, uuidv7(), 'PLAN-' || :run || '-' || i, 'Item ' || i, 'g', 'PURCHASED', true, 0, now(), now()
                FROM generate_series(1, 25) i""").param("run", run).param("tenant", tenant).update();
        owner.sql("""
                INSERT INTO inventory.warehouse (tenant_id, id, store_id, code, name, active, version, created_at,
                    updated_at)
                SELECT :tenant, uuidv7(), uuidv7(), 'PLAN-' || :run, 'Plan ' || s, true, 0, now(), now()
                FROM generate_series(1, 20) s""").param("run", run).param("tenant", tenant).update();
        owner.sql("""
                INSERT INTO inventory.stock_balance (tenant_id, warehouse_id, store_id, stock_item_id, quantity,
                    value, average_cost, last_posting_seq, updated_at)
                SELECT :tenant, w.id, w.store_id, i.id, (row_number() OVER ()) % 7 - 1, 1, 1, 0, now()
                FROM inventory.warehouse w CROSS JOIN inventory.stock_item i
                WHERE w.code = 'PLAN-' || :run AND i.code LIKE 'PLAN-' || :run || '-%'
                  AND w.tenant_id = :tenant AND i.tenant_id = :tenant""")
                .param("run", run).param("tenant", tenant).update();
        owner.sql("""
                INSERT INTO inventory.stock_movement (tenant_id, id, warehouse_id, store_id, stock_item_id, type,
                    quantity, unit_cost, value, currency, source_type, source_id, business_date, occurred_at,
                    posted_at, actor_kind)
                SELECT :tenant, uuidv7(), w.id, w.store_id, i.id, CASE WHEN d % 2 = 0 THEN 'WASTE' ELSE 'SALE_CONSUMPTION' END,
                       -1, 1, -1, 'MZN', 'PLAN', uuidv7(), CAST(:day AS date) + d, now(), now(), 'SYSTEM'
                FROM inventory.warehouse w CROSS JOIN inventory.stock_item i CROSS JOIN generate_series(0, 9) d
                WHERE w.code = 'PLAN-' || :run AND i.code LIKE 'PLAN-' || :run || '-%'
                  AND w.tenant_id = :tenant AND i.tenant_id = :tenant""")
                .param("run", run).param("day", Date.valueOf(DAY)).param("tenant", tenant).update();
    }

    /** The tenant policy's InitPlan as {@code EXPLAIN} prints it: an index condition that leads the key. */
    private static final String TENANT = "\\(tenant_id = \\(InitPlan \\d+\\)\\.col1\\)";

    @Test
    void theStockOfAWarehouseReadsItsBalancesByTenantAndWarehouse() {
        String plan = explain("SELECT count(*) " + InventoryQueries.STOCK, sql -> sql.param("warehouse", warehouse)
                .param("belowMinimum", false).param("negative", false).query(String.class));

        assertThat(plan).containsPattern("(Index (Only )?Scan using|Bitmap Index Scan on) stock_balance_pk")
                .containsPattern("Index Cond: \\(" + TENANT + " AND \\(warehouse_id = .+\\)\\)");
    }

    @Test
    void theValuationOfAStoreFindsItsWarehousesByTenantAndStoreThenTheirBalancesByWarehouse() {
        String plan = explain(InventoryQueries.VALUATION, sql -> sql.param(store).query(String.class));

        assertThat(plan).containsPattern("(Index (Only )?Scan using|Bitmap Index Scan on) warehouse_(store_code|tenant_id)_uk")
                .containsPattern(TENANT).contains("(store_id = '" + store + "'::uuid)")
                .containsPattern("(Index (Only )?Scan using|Bitmap Index Scan on) stock_balance_pk")
                .containsPattern("Index Cond: \\(" + TENANT + " AND \\(warehouse_id = w.id\\)\\)");
    }

    @Test
    void theMovementsOfAStoreAndPeriodUseTheTenantStoreDayIndex() {
        String plan = explain(InventoryQueries.WASTE, sql -> sql.params(store, DAY, DAY.plusDays(3), store, DAY,
                DAY.plusDays(3)).query(String.class));

        assertThat(plan).containsPattern("(Index (Only )?Scan using|Bitmap Index Scan on) stock_movement_store_date_ix")
                .containsPattern("Index Cond: \\(" + TENANT + " AND \\(store_id = '" + store + "'::uuid\\) AND "
                        + "\\(business_date >= '2026-10-01'::date\\) AND \\(business_date <= '2026-10-04'::date\\) AND "
                        + "\\(\\(type\\)::text = 'WASTE'::text\\)\\)")
                .containsPattern("Index Scan using waste_record_store_date_ix on waste_record r");
    }

    /**
     * D169: {@code quantity < 0} is not leakproof, yet it only has to imply the partial index's predicate, which the
     * planner proves from the query's own clause; the policies stay a filter on the rows the index returns (the
     * warehouse is already one tenant's, one store's).
     */
    @Test
    void theNegativeBalancesOfAStoreUseThePartialIndex() {
        String plan = explain(InventoryQueries.NEGATIVE_BALANCES, sql -> sql.param(store).query(String.class));

        assertThat(plan).contains("Index Scan using stock_balance_negative_ix on stock_balance b")
                .contains("Index Cond: (warehouse_id = w.id)")
                .containsPattern("Filter: \\(" + TENANT + " AND \\(\\(InitPlan \\d+\\)\\.col1 OR "
                        + "\\(store_id = ANY \\(\\(InitPlan \\d+\\)\\.col1\\)\\)\\)\\)");
    }

    private String explain(String sql, Function<JdbcClient.StatementSpec, JdbcClient.MappedQuerySpec<String>> bind) {
        return TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> scopes.callForStore(store, "test:plan",
                () -> tx.execute(status -> {
            // ANALYZE needs the table owner: as the pool it is skipped with a warning (ADR 0039)
            for (String table : List.of("stock_balance", "stock_movement", "warehouse", "stock_item", "stock_level")) {
                OwnerDatabase.shared().jdbc().sql("ANALYZE inventory." + table).update();
            }
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            assertThat(jdbc.sql("SELECT current_setting('app.store_ids')").query(String.class).single())
                    .as("explained in one store's scope").isEqualTo("{" + store + "}");
            return String.join("\n", bind.apply(jdbc.sql("EXPLAIN " + sql)).list());
        })));
    }
}
