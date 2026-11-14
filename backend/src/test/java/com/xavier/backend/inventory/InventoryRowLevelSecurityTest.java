package com.xavier.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A71, A72, A75 for inventory (D167, D168), as the application role on raw connections whose store scope the test
 * writes itself (no Spring context, no fixture fallback). One case per table class: STORE ({@code consumption_route},
 * {@code stock_balance}), the open-read {@code warehouse}, CHILD ({@code stock_level} under the open-read warehouse,
 * {@code purchase_order_line}), the two-store {@code stock_transfer} and its lines, and the two D42 probes. Seeded by
 * the owner, which bypasses row-level security.
 */
class InventoryRowLevelSecurityTest {

    static final UUID STORE_A = UUID.randomUUID();
    static final UUID STORE_B = UUID.randomUUID();
    static final UUID STORE_C = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    static UUID item;
    static UUID warehouseA;
    static UUID warehouseB;
    static UUID routeB;
    static UUID supplier;
    static UUID orderB;
    static UUID transferAB;

    static final List<Connection> connections = new ArrayList<>();

    @BeforeAll
    static void seed() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        item = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version, created_at,
                            updated_at) VALUES (?, ?, 'Flour', 'g', 'PURCHASED', true, 0, ?, ?)""")
                .params(item, "RLS-" + item, ts(), ts()).update();
        warehouseA = warehouse(STORE_A);
        warehouseB = warehouse(STORE_B);
        balance(warehouseA, STORE_A);
        balance(warehouseB, STORE_B);
        owner.sql("INSERT INTO inventory.stock_level (warehouse_id, stock_item_id, min_quantity, version) "
                + "VALUES (?, ?, 5, 0)").params(warehouseB, item).update();
        routeB = UUID.randomUUID();
        owner.sql("INSERT INTO inventory.consumption_route (id, store_id, destination_code, warehouse_id, version) "
                + "VALUES (?, ?, NULL, ?, 0)").params(routeB, STORE_B, warehouseB).update();
        supplier = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO inventory.supplier (id, code, name, active, created_at, updated_at, version)
                        VALUES (?, ?, 'Mill', true, ?, ?, 0)""").params(supplier, "RLS-" + supplier, ts(), ts())
                .update();
        orderB = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO inventory.purchase_order (id, store_id, warehouse_id, supplier_id, number, status,
                            created_at, version) VALUES (?, ?, ?, ?, 'PO-1', 'DRAFT', ?, 0)""")
                .params(orderB, STORE_B, warehouseB, supplier, ts()).update();
        owner.sql("""
                        INSERT INTO inventory.purchase_order_line (id, purchase_order_id, stock_item_id, quantity,
                            quantity_base, unit_cost) VALUES (?, ?, ?, 1, 1, 1)""")
                .params(UUID.randomUUID(), orderB, item).update();
        transferAB = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id, from_store_id,
                            to_store_id, status, created_at, version) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, 0)""")
                .params(transferAB, warehouseA, warehouseB, STORE_A, STORE_B, ts()).update();
        owner.sql("INSERT INTO inventory.stock_transfer_line (transfer_id, stock_item_id, quantity_shipped) "
                + "VALUES (?, ?, 1)").params(transferAB, item).update();
    }

    @AfterAll
    static void close() throws SQLException {
        for (Connection connection : connections) {
            connection.close();
        }
    }

    @Test
    void everyInventoryTableIsClassified() {
        RowLevelSecurityCoverageTest.assertModuleClassified("inventory", "warehouse", "stock_transfer",
                "stock_transfer_line");
    }

    /** D167/D172: 16 STORE (warehouse with open reads), 1 SPECIAL, 7 CHILD protected; 10 registered unscoped. */
    @Test
    void inventoryProtectsTwentyFourTablesAndRegistersTen() {
        JdbcClient owner = OwnerDatabase.shared().jdbc();

        assertThat(owner.sql("""
                        SELECT c.relname FROM shared.rls_protected_table p JOIN pg_catalog.pg_class c
                            ON c.oid = p.table_name JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'inventory' ORDER BY 1""").query(String.class).list())
                .containsExactlyInAnyOrder("warehouse", "consumption_route", "inventory_activation",
                        "stock_movement", "stock_balance", "balance_check", "consumed_line", "line_void",
                        "waste_record", "consumption_exception", "cost_variance", "preparation", "purchase_order",
                        "goods_receipt", "supplier_return", "stock_count", "stock_transfer", "stock_level",
                        "purchase_order_line", "goods_receipt_line", "receipt_cost_correction",
                        "supplier_return_line", "stock_count_line", "stock_transfer_line");
        assertThat(owner.sql("""
                        SELECT c.relname FROM shared.rls_unscoped_table u JOIN pg_catalog.pg_class c
                            ON c.oid = u.table_name JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'inventory' ORDER BY 1""").query(String.class).list())
                .containsExactlyInAnyOrder("stock_unit", "stock_item", "stock_item_pack", "supplier",
                        "supplier_item", "recipe", "recipe_version", "recipe_ingredient",
                        "consumption_policy_version", "consumption_policy_rule");
    }

    @Test
    void aStoreTableShowsExactlyTheScopesStores() {
        String byId = "SELECT count(*) FROM inventory.consumption_route WHERE id = ?";

        assertThat(count(scoped(STORE_A), byId, routeB)).as("store A").isZero();
        assertThat(count(scoped(STORE_B), byId, routeB)).as("store B").isOne();
        assertThat(count(scoped(STORE_A, STORE_B), byId, routeB)).as("stores A and B").isOne();
        assertThat(count(allStores(), byId, routeB)).as("all stores").isOne();
        assertThat(count(scoped(), byId, routeB)).as("an empty scope sees nothing, no error").isZero();
    }

    @Test
    void aStoreTableRefusesAnInsertIntoAnotherStoreAndUpdatesNoneOfItsRows() {
        JdbcClient storeA = scoped(STORE_A);

        assertThat(sqlState(() -> storeA.sql("INSERT INTO inventory.consumption_route (id, store_id, "
                        + "destination_code, warehouse_id, version) VALUES (?, ?, 'BAR', ?, 0)")
                .params(UUID.randomUUID(), STORE_B, warehouseB).update())).isEqualTo("42501");
        assertThat(storeA.sql("UPDATE inventory.consumption_route SET version = version + 1 WHERE id = ?")
                .param(routeB).update()).isZero();
    }

    @Test
    void aRowNeverMovesToAnotherStoreWhateverTheScope() {
        String move = "UPDATE inventory.consumption_route SET store_id = ? WHERE id = ?";

        assertThat(sqlState(() -> scoped(STORE_B).sql(move).params(STORE_A, routeB).update())).isEqualTo("RS002");
        assertThat(sqlState(() -> allStores().sql(move).params(STORE_A, routeB).update())).isEqualTo("RS002");
    }

    /** On tables holding rows: the policy's readers are InitPlans, evaluated when a row is first checked. */
    @Test
    void anUnsetScopeFailsClosed() {
        JdbcClient unset = unset();

        assertThat(sqlState(() -> unset.sql("SELECT count(*) FROM inventory.consumption_route").query(Long.class)
                .single())).isEqualTo("RS001");
        assertThat(sqlState(() -> unset.sql("SELECT count(*) FROM inventory.stock_balance").query(Long.class)
                .single())).isEqualTo("RS001");
        assertThat(sqlState(() -> unset.sql("SELECT count(*) FROM inventory.stock_transfer").query(Long.class)
                .single())).isEqualTo("RS001");
    }

    /** D167: the denormalised store of a balance is its warehouse's, enforced by the composite foreign key. */
    @Test
    void aBalanceBelongsToItsWarehousesStore() {
        String balancesOfB = "SELECT count(*) FROM inventory.stock_balance WHERE warehouse_id = ?";

        assertThat(count(scoped(STORE_A), balancesOfB, warehouseB)).isZero();
        assertThat(count(scoped(STORE_B), balancesOfB, warehouseB)).isOne();
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT store_id FROM inventory.stock_balance "
                + "WHERE warehouse_id = ?").param(warehouseB).query(UUID.class).single()).isEqualTo(STORE_B);
        assertThat(sqlState(() -> scoped(STORE_A).sql(insertBalance()).params(warehouseB, STORE_B, item, ts())
                .update())).as("into another store").isEqualTo("42501");
        UUID other = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version, created_at,
                            updated_at) VALUES (?, ?, 'Salt', 'g', 'PURCHASED', true, 0, ?, ?)""")
                .params(other, "RLS-" + other, ts(), ts()).update();
        assertThat(sqlState(() -> OwnerDatabase.shared().jdbc().sql(insertBalance())
                .params(warehouseA, STORE_B, other, ts()).update()))
                .as("a store that is not its warehouse's, even for the owner").isEqualTo("23503");
        assertThat(sqlState(() -> allStores().sql("UPDATE inventory.stock_balance SET store_id = ? "
                + "WHERE warehouse_id = ?").params(STORE_A, warehouseB).update())).isEqualTo("RS002");
    }

    /** D168: warehouse names and codes are configuration every scope reads; only its store's scope writes it. */
    @Test
    void anotherStoresWarehouseIsReadableButNotWritable() {
        JdbcClient storeA = scoped(STORE_A);

        assertThat(count(storeA, "SELECT count(*) FROM inventory.warehouse WHERE id = ?", warehouseB)).isOne();
        assertThat(count(unset(), "SELECT count(*) FROM inventory.warehouse WHERE id = ?", warehouseB))
                .as("open reads need no scope").isOne();
        assertThat(sqlState(() -> storeA.sql("""
                        INSERT INTO inventory.warehouse (id, store_id, code, name, active, version, created_at,
                            updated_at) VALUES (?, ?, 'X', 'X', true, 0, ?, ?)""")
                .params(UUID.randomUUID(), STORE_B, ts(), ts()).update())).isEqualTo("42501");
        assertThat(storeA.sql("UPDATE inventory.warehouse SET name = 'Mine' WHERE id = ?").param(warehouseB)
                .update()).isZero();
        assertThat(storeA.sql("DELETE FROM inventory.warehouse WHERE id = ?").param(warehouseB).update()).isZero();
        assertThat(sqlState(() -> allStores().sql("UPDATE inventory.warehouse SET store_id = ? WHERE id = ?")
                .params(STORE_A, warehouseB).update())).isEqualTo("RS002");
    }

    /** The CHILD policy checks the parent's store itself: the open-read warehouse does not open its levels. */
    @Test
    void aChildFollowsItsParentsStore() {
        JdbcClient storeA = scoped(STORE_A);

        assertThat(count(storeA, "SELECT count(*) FROM inventory.stock_level WHERE warehouse_id = ?", warehouseB))
                .isZero();
        assertThat(count(scoped(STORE_B), "SELECT count(*) FROM inventory.stock_level WHERE warehouse_id = ?",
                warehouseB)).isOne();
        UUID other = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version, created_at,
                            updated_at) VALUES (?, ?, 'Sugar', 'g', 'PURCHASED', true, 0, ?, ?)""")
                .params(other, "RLS-" + other, ts(), ts()).update();
        assertThat(sqlState(() -> storeA.sql("INSERT INTO inventory.stock_level (warehouse_id, stock_item_id, "
                + "min_quantity, version) VALUES (?, ?, 1, 0)").params(warehouseB, other).update()))
                .as("a level under another store's warehouse").isEqualTo("42501");
        assertThat(count(storeA, "SELECT count(*) FROM inventory.purchase_order_line WHERE purchase_order_id = ?",
                orderB)).isZero();
        assertThat(sqlState(() -> storeA.sql("""
                        INSERT INTO inventory.purchase_order_line (id, purchase_order_id, stock_item_id, quantity,
                            quantity_base, unit_cost) VALUES (?, ?, ?, 1, 1, 1)""")
                .params(UUID.randomUUID(), orderB, other).update()))
                .as("a line of another store's order: its draft guard sees no parent (restrict_violation)")
                .isEqualTo("23001");
        assertThat(sqlState(() -> allStores().sql("UPDATE inventory.stock_level SET warehouse_id = ? "
                + "WHERE warehouse_id = ?").params(warehouseA, warehouseB).update())).isEqualTo("RS002");
    }

    /** D168: a transfer and its lines are seen at either end, by no third store, and never change ends. */
    @Test
    void aTransferIsSeenAtBothEndsOnly() {
        String transfer = "SELECT count(*) FROM inventory.stock_transfer WHERE id = ?";
        String lines = "SELECT count(*) FROM inventory.stock_transfer_line WHERE transfer_id = ?";

        assertThat(count(scoped(STORE_A), transfer, transferAB)).as("the source").isOne();
        assertThat(count(scoped(STORE_B), transfer, transferAB)).as("the destination").isOne();
        assertThat(count(scoped(STORE_C), transfer, transferAB)).as("a third store").isZero();
        assertThat(count(scoped(STORE_A), lines, transferAB)).isOne();
        assertThat(count(scoped(STORE_B), lines, transferAB)).isOne();
        assertThat(count(scoped(STORE_C), lines, transferAB)).isZero();
        assertThat(scoped(STORE_C).sql("UPDATE inventory.stock_transfer SET notes = 'x' WHERE id = ?")
                .param(transferAB).update()).isZero();
        assertThat(sqlState(() -> scoped(STORE_C).sql("""
                        INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id, from_store_id,
                            to_store_id, status, created_at, version) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, 0)""")
                .params(UUID.randomUUID(), warehouseA, warehouseB, STORE_A, STORE_B, ts()).update()))
                .as("a transfer between two other stores").isEqualTo("42501");
        UUID other = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version, created_at,
                            updated_at) VALUES (?, ?, 'Oil', 'ml', 'PURCHASED', true, 0, ?, ?)""")
                .params(other, "RLS-" + other, ts(), ts()).update();
        assertThat(sqlState(() -> scoped(STORE_C).sql("INSERT INTO inventory.stock_transfer_line (transfer_id, "
                + "stock_item_id, quantity_shipped) VALUES (?, ?, 1)").params(transferAB, other).update()))
                .as("a line of another stores' transfer").isEqualTo("42501");
        for (String end : List.of("from_store_id", "to_store_id")) {
            assertThat(sqlState(() -> allStores().sql("UPDATE inventory.stock_transfer SET " + end + " = ? "
                    + "WHERE id = ?").params(STORE_C, transferAB).update())).as(end).isEqualTo("RS002");
        }
        UUID otherTransfer = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO inventory.stock_transfer (id, from_warehouse_id, to_warehouse_id, from_store_id,
                            to_store_id, status, created_at, version) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, 0)""")
                .params(otherTransfer, warehouseA, warehouseB, STORE_A, STORE_B, ts()).update();
        assertThat(sqlState(() -> allStores().sql("UPDATE inventory.stock_transfer_line SET transfer_id = ? "
                + "WHERE transfer_id = ?").params(otherTransfer, transferAB).update())).isEqualTo("RS002");
    }

    /** D42 under RLS: the probes see every store, so another store's resource answers 403, not 404. */
    @Test
    void theProbesSeeEveryStore() {
        JdbcClient storeC = scoped(STORE_C);

        assertThat(storeC.sql("SELECT shared.store_of('inventory.purchase_order', ?)").param(orderB)
                .query(UUID.class).single()).isEqualTo(STORE_B);
        assertThat(storeC.sql("SELECT from_store_id, to_store_id FROM inventory.stock_transfer_stores(?)")
                .param(transferAB).query().singleRow())
                .isEqualTo(Map.of("from_store_id", STORE_A, "to_store_id", STORE_B));
        assertThat(storeC.sql("SELECT count(*) FROM inventory.stock_transfer_stores(?)").param(UUID.randomUUID())
                .query(Long.class).single()).as("no such transfer").isZero();
    }

    private static UUID warehouse(UUID store) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO inventory.warehouse (id, store_id, code, name, active, version, created_at,
                            updated_at) VALUES (?, ?, 'MAIN', 'Main', true, 0, ?, ?)""")
                .params(id, store, ts(), ts()).update();
        return id;
    }

    private static void balance(UUID warehouse, UUID store) {
        OwnerDatabase.shared().jdbc().sql(insertBalance()).params(warehouse, store, item, ts()).update();
    }

    private static String insertBalance() {
        return """
                INSERT INTO inventory.stock_balance (warehouse_id, store_id, stock_item_id, quantity, value,
                    average_cost, last_posting_seq, updated_at) VALUES (?, ?, ?, 0, 0, 0, 0, ?)""";
    }

    private static java.sql.Timestamp ts() {
        return java.sql.Timestamp.from(NOW);
    }

    private static long count(JdbcClient jdbc, String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }

    private static JdbcClient scoped(UUID... stores) {
        String ids = Arrays.stream(stores).map(UUID::toString).sorted().collect(Collectors.joining(",", "{", "}"));
        return connect("off", ids);
    }

    private static JdbcClient allStores() {
        return connect("on", "{}");
    }

    private static JdbcClient unset() {
        return connect("", "");
    }

    private static JdbcClient connect(String allStores, String storeIds) {
        try {
            Connection connection = DriverManager.getConnection(TestcontainersConfiguration.postgres().getJdbcUrl(),
                    TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword());
            connections.add(connection);
            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            jdbc.sql("SELECT set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), "
                    + "set_config('app.scope_origin', 'test:inventory-rls', false)").params(allStores, storeIds)
                    .query().listOfRows();
            return jdbc;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sqlState(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("refused").isNotNull();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("no SQL state in " + thrown, thrown);
    }
}
