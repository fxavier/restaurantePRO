package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static com.xavier.backend.orders.OrdersFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.CodedException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@link OrdersApi} as settlement (S16) calls it: inside its own transaction, in a POS session; and
 * {@link OrdersProductionApi} as production's listener (S15) calls it, with no session.
 */
@OrdersModuleTest
class OrdersApiTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersProductionApi productionApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    DiningApi dining;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOrderWithARound() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, employee, terminal));
    }

    @Test
    void theBillingViewHasTheConfirmedLinesAmountsAndDueButNoDrafts() throws Exception {
        UUID line = confirmed("2", 0);
        fixtures.draft(shop, order, "5", employee, terminal);

        OrderBillingView view = inPos(() -> ordersApi.billingView(order));

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.lifecycle()).isEqualTo(OrderLifecycle.OPEN);
        assertThat(view.billingLock()).isEqualTo(BillingLock.NONE);
        assertThat(view.storeId()).isEqualTo(shop.store());
        assertThat(view.lines()).containsExactly(new BillingLine(line, "Coca-Cola 33cl", new BigDecimal("2.000"),
                mzn("12.50"), mzn("25.00"), mzn("21.55"), mzn("3.45"), view.lines().getFirst().taxCode(),
                new BigDecimal("16.0000")));
        assertThat(view.due()).isEqualTo(mzn("25.00"));
    }

    @Test
    void voidedQuantityLeavesTheBillAndAFullyVoidedLineIsNotBilled() throws Exception {
        UUID partly = confirmed("3", 0);
        UUID fully = confirmed("1", 1);
        voided(partly, "1");
        voided(fully, "1");

        OrderBillingView view = inPos(() -> ordersApi.billingView(order));

        assertThat(view.lines()).extracting(BillingLine::lineId).containsExactly(partly);
        assertThat(view.lines().getFirst().netQty()).isEqualByComparingTo("2");
        assertThat(view.due()).isEqualTo(mzn("25.00"));
    }

    @Test
    void theBillKeepsTheConfirmedPriceAfterARepublicationWithNewPrices() throws Exception {
        confirmed("2", 0);
        fixtures.reprice(shop, "20.00");
        fixtures.republish(shop.store());

        assertThat(inPos(() -> ordersApi.billingView(order)).due()).isEqualTo(mzn("25.00"));
    }

    @Test
    void theOpenBillingViewsAreTheStoresOpenOrders() throws Exception {
        UUID other = id(fixtures.openTable(shop, fixtures.newTable(shop, "M2"), employee, terminal,
                UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString().transform(OrdersFixtures::json));

        assertThat(inPos(() -> ordersApi.openBillingViews(shop.store()))).extracting(OrderBillingView::orderId)
                .containsExactlyInAnyOrder(order, other);
    }

    @Test
    void thePaymentLockMovesTheVersionOnceSoAStaleRoundLosesAndRoundsAreStillAllowed() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        inTx(() -> ordersApi.lockForPayment(order));
        inTx(() -> ordersApi.lockForPayment(order));

        OrderBillingView view = inPos(() -> ordersApi.billingView(order));
        assertThat(view.billingLock()).isEqualTo(BillingLock.PAYMENT);
        assertThat(view.version()).isEqualTo(1);
        fixtures.confirm(shop, order, 0, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isConflict());
        fixtures.confirm(shop, order, 1, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        inTx(() -> ordersApi.releasePaymentLock(order));
        assertThat(inPos(() -> ordersApi.billingView(order)).billingLock()).isEqualTo(BillingLock.NONE);
    }

    @Test
    void anOrderFrozenForItsInvoiceRefusesNewLinesAndRounds() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        inTx(() -> ordersApi.freezeForInvoice(order));
        inTx(() -> ordersApi.freezeForInvoice(order));

        assertThat(inPos(() -> ordersApi.billingView(order)).version()).isEqualTo(1);
        fixtures.confirm(shop, order, 1, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_FROZEN"));
        fixtures.addLine(shop, order, "{\"itemId\": \"%s\", \"quantity\": 1}".formatted(shop.item()), employee,
                terminal, UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_FROZEN"));
    }

    @Test
    void closingAtTheExpectedVersionClosesTheOrderAndFreesItsTable() throws Exception {
        confirmed("1", 0);

        assertThatThrownBy(() -> inTx(() -> ordersApi.close(order, 0, employee)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        inTx(() -> ordersApi.close(order, 1, employee));

        assertThat(inPos(() -> ordersApi.billingView(order)).lifecycle()).isEqualTo(OrderLifecycle.CLOSED);
        assertThat(inPos(() -> dining.tableState(shop.table())).openOrderId()).isNull();
        assertThat(fixtures.audits("ORDER_CLOSED", order)).isOne();
        assertThatThrownBy(() -> inTx(() -> ordersApi.close(order, 2, employee)))
                .isInstanceOfSatisfying(CodedException.class, e -> assertThat(e.code()).isEqualTo("ORDER_NOT_OPEN"));
        fixtures.openTable(shop, shop.table(), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
    }

    @Test
    void openOrdersAreCountedUpToABusinessDay() throws Exception {
        LocalDate day = inPos(() -> ordersApi.billingView(order)).businessDay();

        assertThat(inPos(() -> ordersApi.countOpen(shop.store(), day))).isOne();
        assertThat(inPos(() -> ordersApi.countOpen(shop.store(), day.minusDays(1)))).isZero();
        inTx(() -> ordersApi.close(order, 0, employee));
        assertThat(inPos(() -> ordersApi.countOpen(shop.store(), day))).isZero();
    }

    @Test
    void writesNeedTheCallersTransactionAndAnUnknownOrderIsNotFound() {
        assertThatThrownBy(() -> OrdersActors.runAsPos(shop.store(), employee, terminal,
                () -> run(() -> ordersApi.lockForPayment(order))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inPos(() -> ordersApi.billingView(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void dispatchingARoundIsIdempotentLeavesTheOrderVersionAndNeedsNoSession() throws Exception {
        confirmed("1", 0);
        UUID batch = jdbc.sql("SELECT id FROM orders.order_batch WHERE order_id = ?").param(order)
                .query(UUID.class).single();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> productionApi.markBatchDispatched(batch));
        java.time.OffsetDateTime firstDispatch = dispatchedAt(batch);
        tx.executeWithoutResult(status -> productionApi.markBatchDispatched(batch));

        assertThat(firstDispatch).isNotNull();
        assertThat(dispatchedAt(batch)).isEqualTo(firstDispatch);
        assertThat(fixtures.audits("ORDER_BATCH_DISPATCHED", batch)).isOne();
        assertThat(inPos(() -> ordersApi.billingView(order)).version()).isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> productionApi.markBatchDispatched(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void twoConcurrentDispatchNoticesMakeExactlyOneTransition() throws Exception {
        confirmed("1", 0);
        UUID batch = jdbc.sql("SELECT id FROM orders.order_batch WHERE order_id = ?").param(order)
                .query(UUID.class).single();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        OrdersFixtures.race(
                () -> tx.execute(status -> dispatch(batch)),
                () -> tx.execute(status -> dispatch(batch)));

        assertThat(fixtures.audits("ORDER_BATCH_DISPATCHED", batch)).isOne();
        assertThat(dispatchedAt(batch)).isNotNull();
    }

    boolean dispatch(UUID batch) {
        productionApi.markBatchDispatched(batch);
        return true;
    }

    UUID confirmed(String quantity, long orderVersion) throws Exception {
        UUID line = id(fixtures.draft(shop, order, quantity, employee, terminal));
        JsonNode batch = json(fixtures.confirm(shop, order, orderVersion, shop.catalogVersion(), List.of(line),
                employee, terminal, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(batch.get("lines").get(0).get("id").asString()).isEqualTo(line.toString());
        return line;
    }

    /** A void as S14 writes it (the fact only). */
    void voided(UUID line, String quantity) {
        jdbc.sql("""
                INSERT INTO orders.order_line_void (id, order_id, line_id, quantity, reason, wasted,
                    operator_employee_id, approver_employee_id, terminal_id, voided_at)
                VALUES (gen_random_uuid(), ?, ?, ?, 'wrong dish', false, gen_random_uuid(), gen_random_uuid(),
                    gen_random_uuid(), now())""").param(order).param(line).param(new BigDecimal(quantity)).update();
    }

    java.time.OffsetDateTime dispatchedAt(UUID batch) {
        return jdbc.sql("SELECT dispatched_at FROM orders.order_batch WHERE id = ?").param(batch)
                .query(java.time.OffsetDateTime.class).single();
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), employee, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }

    void inTx(Runnable action) {
        inPos(() -> run(action));
    }

    static Void run(Runnable action) {
        action.run();
        return null;
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
