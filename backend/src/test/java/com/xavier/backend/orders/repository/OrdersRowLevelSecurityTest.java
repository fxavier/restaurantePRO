package com.xavier.backend.orders.repository;

import static com.xavier.backend.dining.AppRoleSession.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.dining.AppRoleSession;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A71/A72 for orders (D167, D168, D171): as the application role with a hand-written store scope (no
 * {@code AccessPolicy} in front), a store sees and writes only its own orders, rounds, transfers, deliveries and zones,
 * and the lines, voids, components, adjustments, splits, transfer lines and delivery history under them; no scope moves
 * a row to another store or a child to another parent; an unset scope fails closed; the D42 probe still finds another
 * store's order. Seeded as the owner.
 */
class OrdersRowLevelSecurityTest {

    static final Map<String, String> STORE_TABLES = Map.of("order_header", "store_scope", "order_batch",
            "store_scope", "order_transfer", "store_scope", "delivery", "store_scope", "delivery_zone", "store_scope");
    /** D167: each child and the parent its policy looks up. */
    static final Map<String, String> CHILD_PARENTS = Map.of("order_line", "orders.order_header",
            "order_line_component", "orders.order_header", "order_line_price_adjustment", "orders.order_header",
            "order_line_split", "orders.order_header", "order_line_void", "orders.order_header",
            "order_transfer_line", "orders.order_transfer", "delivery_event", "orders.delivery");

    final UUID storeA = UUID.randomUUID();
    final UUID storeB = UUID.randomUUID();
    final JdbcClient owner = OwnerDatabase.shared().jdbc();

    UUID orderA;
    UUID orderB;
    UUID lineA;
    UUID lineB;
    AppRoleSession app;

    @BeforeEach
    void seed() throws SQLException {
        orderA = order(storeA);
        orderB = order(storeB);
        lineA = draftLine(orderA);
        lineB = draftLine(orderB);
        app = AppRoleSession.open();
    }

    @AfterEach
    void close() throws SQLException {
        app.close();
    }

    @Test
    void everyOrdersTableIsClassifiedAsD167Says() {
        RowLevelSecurityCoverageTest.assertModuleClassified("orders");

        Map<String, String> policies = owner.sql("""
                        SELECT c.relname, p.polname FROM pg_catalog.pg_policy p
                            JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
                        WHERE c.relnamespace = 'orders'::regnamespace""")
                .query((row, n) -> Map.entry(row.getString(1), row.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(policies).hasSize(12).containsAllEntriesOf(STORE_TABLES);
        CHILD_PARENTS.forEach((child, parent) -> {
            assertThat(policies).containsEntry(child, "parent_scope");
            assertThat(owner.sql("SELECT pg_catalog.pg_get_expr(polqual, polrelid) FROM pg_catalog.pg_policy "
                    + "WHERE polrelid = CAST(? AS regclass)").param("orders." + child).query(String.class).single())
                    .as("%s looks up %s", child, parent).contains("FROM " + parent + " p");
        });
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresOrdersAndTheirLines() {
        assertThat(ordersSeen(app.stores(storeA))).containsExactly(orderA);
        assertThat(linesSeen(app.stores(storeA))).containsExactly(lineA);
        assertThat(ordersSeen(app.stores(storeA, storeB))).containsExactlyInAnyOrder(orderA, orderB);
        assertThat(linesSeen(app.allStores())).containsExactlyInAnyOrder(lineA, lineB);
        assertThat(ordersSeen(app.stores())).as("an explicit empty scope sees nothing, without an error").isEmpty();
        assertThat(linesSeen(app.stores())).isEmpty();
    }

    @Test
    void anUnsetScopeFailsClosed() {
        app.unset();

        assertRefused(() -> ordersSeen(app), Kind.SCOPE_MISSING);
        assertRefused(() -> linesSeen(app), Kind.SCOPE_MISSING);
    }

    @Test
    void aStoreScopeWritesNothingOfAnotherStore() {
        JdbcClient jdbc = app.stores(storeA).jdbc();

        assertRefused(() -> insertOrder(jdbc, storeB), Kind.WRITE_REFUSED);
        assertRefused(() -> insertDraftLine(jdbc, orderB), Kind.WRITE_REFUSED);
        assertThat(jdbc.sql("UPDATE orders.order_header SET version = version + 1 WHERE id = ?").param(orderB)
                .update()).as("another store's order is not there to update").isZero();
        assertThat(jdbc.sql("DELETE FROM orders.order_line WHERE id = ?").param(lineB).update()).isZero();
        assertThat(jdbc.sql("UPDATE orders.order_line SET quantity = 2 WHERE id = ?").param(lineA).update()).isOne();
    }

    @Test
    void noScopeMovesAnOrderOrALineToAnotherStore() {
        String move = "UPDATE orders.order_header SET store_id = ? WHERE id = ?";
        assertRefused(() -> app.stores(storeA).jdbc().sql(move).param(storeB).param(orderA).update(),
                Kind.STORE_CHANGE);
        assertRefused(() -> app.allStores().jdbc().sql(move).param(storeB).param(orderA).update(),
                Kind.STORE_CHANGE);
        assertRefused(() -> app.allStores().jdbc().sql("UPDATE orders.order_line SET order_id = ? WHERE id = ?")
                .param(orderB).param(lineA).update(), Kind.STORE_CHANGE);
    }

    @Test
    void aDeliveryEventAndATransferLineFollowTheirParentsStore() {
        UUID zoneB = zone(storeB);
        delivery(orderB, storeB, zoneB);
        UUID orderB2 = order(storeB);
        UUID transferB = transfer(storeB, orderB, orderB2);
        JdbcClient storeAScope = app.stores(storeA).jdbc();

        assertRefused(() -> storeAScope.sql("""
                INSERT INTO orders.delivery_event (id, order_id, to_status, at, actor_employee_id)
                VALUES (gen_random_uuid(), ?, 'PENDING', now(), gen_random_uuid())""").param(orderB).update(),
                Kind.WRITE_REFUSED);
        assertRefused(() -> storeAScope.sql("""
                INSERT INTO orders.order_transfer_line (id, transfer_id, source_order_id, target_order_id,
                    source_line_id, target_line_id, quantity)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 1)""")
                .param(transferB).param(orderB).param(orderB2).param(lineB).update(), Kind.WRITE_REFUSED);
        assertThat(storeAScope.sql("SELECT count(*) FROM orders.delivery_zone WHERE id = ?").param(zoneB)
                .query(Long.class).single()).isZero();
    }

    /** D42 under RLS: the order is hidden from store A's read, yet its store is probed (then 403, not 404). */
    @Test
    void theProbeFindsAnotherStoresOrderDeliveryAndZone() {
        UUID zoneB = zone(storeB);
        delivery(orderB, storeB, zoneB);
        StoreProbe probe = new StoreProbe(app.stores(storeA).jdbc());

        assertThat(probe.storeOf("orders.order_header", orderB)).contains(storeB);
        assertThat(probe.storeOf("orders.delivery", orderB)).contains(storeB);
        assertThat(probe.storeOf("orders.delivery_zone", zoneB)).contains(storeB);
    }

    private List<UUID> ordersSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM orders.order_header WHERE id IN (?, ?)").param(orderA).param(orderB)
                .query(UUID.class).list();
    }

    private List<UUID> linesSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM orders.order_line WHERE id IN (?, ?)").param(lineA).param(lineB)
                .query(UUID.class).list();
    }

    private UUID order(UUID store) {
        return insertOrder(owner, store);
    }

    private UUID draftLine(UUID order) {
        return insertDraftLine(owner, order);
    }

    static UUID insertOrder(JdbcClient jdbc, UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_header (id, store_id, terminal_id, order_number, mode, table_id, lifecycle,
                    billing_lock, business_day, currency, opened_at, opened_by_employee_id, next_batch_no, version)
                VALUES (?, ?, gen_random_uuid(), nextval('orders.order_number_seq'), 'COUNTER', NULL, 'OPEN',
                    'NONE', current_date, 'MZN', now(), gen_random_uuid(), 1, 0)""")
                .param(id).param(store).update();
        return id;
    }

    static UUID insertDraftLine(JdbcClient jdbc, UUID order) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders.order_line (id, order_id, status, item_id, quantity, created_at,
                    created_by_employee_id, version)
                VALUES (?, ?, 'DRAFT', gen_random_uuid(), 1, now(), gen_random_uuid(), 0)""")
                .param(id).param(order).update();
        return id;
    }

    private UUID zone(UUID store) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO orders.delivery_zone (id, store_id, code, name, fee_item_id, active, version)
                VALUES (?, ?, ?, 'Zone', gen_random_uuid(), true, 0)""")
                .param(id).param(store).param("Z" + id.toString().substring(0, 8)).update();
        return id;
    }

    private void delivery(UUID order, UUID store, UUID zone) {
        owner.sql("""
                INSERT INTO orders.delivery (order_id, store_id, customer_id, address_line1, zone_id, zone_code,
                    status, version)
                VALUES (?, ?, gen_random_uuid(), 'Rua 1', ?, 'Z', 'PENDING', 0)""")
                .param(order).param(store).param(zone).update();
    }

    private UUID transfer(UUID store, UUID source, UUID target) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO orders.order_transfer (id, store_id, source_order_id, target_order_id, employee_id,
                    terminal_id, transferred_at)
                VALUES (?, ?, ?, ?, gen_random_uuid(), gen_random_uuid(), now())""")
                .param(id).param(store).param(source).param(target).update();
        return id;
    }
}
