package com.xavier.backend.inventory.consumption.flow;

import static com.xavier.backend.inventory.consumption.flow.LiveStore.roundPublications;
import static com.xavier.backend.inventory.consumption.flow.LiveStore.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.repository.BalanceDrift;
import com.xavier.backend.inventory.service.StockBalanceReconciliationJob;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A74 for inventory: its actor-less entry points complete in their D166 scope, with no {@code RS001} and no other
 * store-scope violation, on threads that have production's semantics (no fixture fallback). The consumption
 * listeners run on the listener thread in the store of their event ({@code OrderBatchConfirmed},
 * {@code OrderLinesVoided}); the nightly reconcile job, started on a thread with no scope at all, runs in
 * {@code STOCK_RECONCILE} (every store), so it checks and records every store's balances.
 */
@ConsumptionFlowTest
class InventoryActorlessScopeTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures sales;

    @Autowired
    InventoryFixtures inventory;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    StockBalanceReconciliationJob reconciliation;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theConsumptionListenersCompleteInTheirEventsStore() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
        Sale sale = sales.sale("1");
        waitUntil(() -> roundPublications(jdbc, sale.order(), true) == 1, "the first round's publication");
        LiveStore live = LiveStore.of(sale, mvc, inventory);
        double violations = violations();

        var cashier = sale.cashier();
        sales.confirmRound(sale.shop(), cashier, sale.order(), "2", sales.orderVersion(cashier, sale.order()));
        waitUntil(() -> live.movements(jdbc, "SALE_CONSUMPTION") == 1, "the round consumed by its listener");
        UUID line = jdbc.sql("""
                        SELECT l.id FROM orders.order_line l JOIN orders.order_batch b ON b.id = l.batch_id
                        WHERE l.order_id = ? ORDER BY b.batch_no DESC LIMIT 1""")
                .param(sale.order()).query(UUID.class).single();
        voidLine(sale, line);
        waitUntil(() -> live.movements(jdbc, "CONSUMPTION_REVERSAL") == 1, "the void reversed by its listener");

        assertThat(roundPublications(jdbc, sale.order(), false)).as("no round publication left incomplete").isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE listener_id LIKE 'com.xavier.backend.inventory.consumption.VoidConsumptionListener%'
                          AND serialized_event LIKE :order AND completion_date IS NULL""")
                .param("order", "%" + sale.order() + "%").query(Long.class).single()).isZero();
        assertThat(live.flourInStock(jdbc)).as("2 sold at 250 g, 1 voided back").isEqualByComparingTo("99.75");
        assertThat(violations()).as("no store-scope violation on the listener threads").isEqualTo(violations);
    }

    @Test
    void theReconcileJobChecksEveryStoreFromAThreadWithNoScope() throws Exception {
        UUID storeA = inventory.newStore();
        UUID storeB = inventory.newStore();
        UUID warehouseA = inventory.newWarehouse(storeA, "ALLOW");
        UUID warehouseB = inventory.newWarehouse(storeB, "ALLOW");
        UUID item = inventory.newStockItem("kg");
        for (UUID warehouse : List.of(warehouseA, warehouseB)) {
            UUID store = warehouse.equals(warehouseA) ? storeA : storeB;
            OwnerDatabase.shared().jdbc().sql("""
                            INSERT INTO inventory.stock_balance (warehouse_id, store_id, stock_item_id, quantity, value,
                                average_cost, last_posting_seq, updated_at) VALUES (?, ?, ?, 1, 2, 2, 0, now())""")
                    .params(warehouse, store, item).update(); // a balance no movement explains: a drift
        }
        double violations = violations();
        AtomicReference<List<BalanceDrift>> found = new AtomicReference<>();

        CompletableFuture.runAsync(() -> found.set(reconciliation.reconcile()), command -> new Thread(command).start())
                .get(60, TimeUnit.SECONDS);

        assertThat(found.get()).extracting(BalanceDrift::storeId).contains(storeA, storeB);
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT count(*) FROM inventory.balance_check WHERE store_id IN (?, ?) AND drifting_balances = 1""")
                .params(storeA, storeB).query(Long.class).single()).isEqualTo(2);
        assertThat(violations()).isEqualTo(violations);
    }

    private void voidLine(Sale sale, UUID line) throws Exception {
        long version = jdbc.sql("SELECT version FROM orders.order_header WHERE id = ?").param(sale.order())
                .query(Long.class).single();
        mvc.perform(post(SettlementFixtures.ORDERS + "/" + sale.order() + "/voids")
                        .with(OrdersActors.pos(sale.store(), UUID.randomUUID(), UUID.randomUUID(),
                                Permission.ORDER_VOID)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "lineId": "%s", "quantity": 1, "reason": "error", "wasted": false}"""
                                .formatted(version, line)))
                .andExpect(status().isCreated());
    }

    private double violations() {
        return meters.find("store_scope_violations").counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
