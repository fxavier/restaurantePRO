package com.xavier.backend.orders.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.orders.BootstrapSeed;
import com.xavier.backend.AppRoleSession;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * D169/D171 plans of the hot orders reads under row-level security, as the application role in one store's scope:
 * the policy is an InitPlan filter on top, and each query's own index still drives the scan. The open orders of a
 * store ({@code findByStoreIdAndLifecycleOrderByOpenedAt}, the POS order list) are read through an index led by
 * {@code store_id} (which of the two store indexes is the planner's call), never through every open order of the
 * installation ({@code order_header_open_table_uk}) or a sequential scan; the lines of one order
 * ({@code findByOrderIdOrderById}, every order view) stay on {@code order_line_order_status_ix}, with the CHILD policy
 * probing the order by its primary key. Deterministic seed (20 stores × 201 orders of 2020 × 3 lines; the store
 * under test has one open order, the 19 others 101 each, so the installation's open orders far outnumber the
 * store's), then {@code ANALYZE} as the owner and {@code EXPLAIN} in one transaction with
 * {@code enable_seqscan = off}.
 */
class OrdersPlanTest {

    static final String INDEX_NODE = "(Index Scan using|Index Only Scan using|Bitmap Index Scan on) ";

    static UUID store;
    static UUID order;
    static AppRoleSession app;

    @BeforeAll
    static void seed() throws SQLException {
        List<UUID> stores = OwnerDatabase.shared().call(owner -> {
            BootstrapSeed.bind(owner);
            List<UUID> seeded = seedStores(owner);
            // D189: the plan is measured in tenant A with another tenant's rows of the same shape present
            BootstrapSeed.bind(owner, TenantTestSupport.B);
            seedStores(owner);
            return seeded;
        });
        store = stores.getFirst();
        order = OwnerDatabase.shared().jdbc().sql("SELECT id FROM orders.order_header WHERE store_id = ? LIMIT 1")
                .param(store).query(UUID.class).single();
        app = AppRoleSession.open().stores(store);
    }

    /** 20 stores of orders (the first with one open order, the rest with 100) with 3 draft lines each. */
    private static List<UUID> seedStores(JdbcClient owner) {
        List<UUID> seeded = owner.sql("SELECT gen_random_uuid() FROM generate_series(1, 20)").query(UUID.class)
                .list();
        owner.sql("""
                INSERT INTO orders.order_header (id, store_id, terminal_id, order_number, mode, table_id,
                    lifecycle, billing_lock, business_day, currency, opened_at, opened_by_employee_id,
                    next_batch_no, version, closed_at, closed_by_employee_id)
                SELECT uuidv7(), s.store, gen_random_uuid(), nextval('orders.order_number_seq'), 'COUNTER', NULL,
                    CASE WHEN o.open THEN 'OPEN' ELSE 'CLOSED' END, 'NONE', DATE '2020-01-01', 'MZN',
                    TIMESTAMPTZ '2020-01-01 12:00Z' + n * interval '1 minute', gen_random_uuid(), 1, 0,
                    CASE WHEN NOT o.open THEN TIMESTAMPTZ '2020-01-01 23:00Z' END,
                    CASE WHEN NOT o.open THEN gen_random_uuid() END
                FROM unnest(CAST(:stores AS uuid[])) AS s(store), generate_series(1, 201) AS n,
                    LATERAL (SELECT n = 1 OR (s.store <> :first AND n <= 101) AS open) AS o""")
                .param("stores", seeded.toArray(UUID[]::new)).param("first", seeded.getFirst()).update();
        owner.sql("""
                INSERT INTO orders.order_line (id, order_id, status, item_id, quantity, created_at,
                    created_by_employee_id, version)
                SELECT uuidv7(), o.id, 'DRAFT', gen_random_uuid(), 1, o.opened_at, gen_random_uuid(), 0
                FROM orders.order_header o, generate_series(1, 3)
                WHERE o.store_id = ANY (CAST(:stores AS uuid[]))""")
                .param("stores", seeded.toArray(UUID[]::new)).update();
        return seeded;
    }

    @AfterAll
    static void close() throws SQLException {
        app.close();
    }

    @Test
    void theOpenOrdersOfAStoreAreReadThroughAStoreIndex() {
        String plan = explain("""
                SELECT * FROM orders.order_header
                WHERE store_id = :store AND lifecycle = 'OPEN' ORDER BY opened_at""");

        assertThat(plan).as("the store_scope policy, once per statement").contains("InitPlan")
                .doesNotContain("Seq Scan")
                .containsPattern(INDEX_NODE + "order_header_store_\\w+_ix")
                .containsPattern("Index Cond: \\(\\(tenant_id = \\(InitPlan \\d+\\)\\.col1\\) AND \\(store_id = '[0-9a-f-]+'::uuid\\)");
    }

    @Test
    void theLinesOfOneOrderStayOnTheOrderIndexAndProbeTheOrderByItsKey() {
        String plan = explain("SELECT * FROM orders.order_line WHERE order_id = :order ORDER BY id");

        assertThat(plan).as("the parent_scope policy").contains("InitPlan")
                .containsPattern(INDEX_NODE + "order_line_order_status_ix")
                .containsPattern("Index Cond: \\(order_id = '[0-9a-f-]+'::uuid\\)")
                .containsPattern(INDEX_NODE + "order_header_(pk|tenant_id_uk)");
    }

    private static String explain(String sql) {
        OwnerDatabase.shared().jdbc().sql("ANALYZE orders.order_header").update();
        OwnerDatabase.shared().jdbc().sql("ANALYZE orders.order_line").update();
        return app.inTransaction(jdbc -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + sql).param("store", store).param("order", order)
                    .query(String.class).list());
        });
    }
}
