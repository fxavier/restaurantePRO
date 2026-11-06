package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D100: a void names the round line its quantity was consumed from, so inventory reverses the consumption of the
 * line it posted, however the voided line reached its order.
 */
@OrdersModuleTest
class VoidFactsConsumedLineTest {

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID approver = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;

    @BeforeEach
    void aRoundOfThree() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
    }

    @Test
    void aVoidOnARoundLineNamesThatLine() {
        VoidView voided = voidOf(order, line, 1);

        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).consumedLineId()).isEqualTo(line);
    }

    @Test
    void aVoidOnALineTransferredInNamesTheSourceRoundLine() throws Exception {
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        UUID copy = transferOne(order, line, target);

        VoidView voided = voidOf(target, copy, version(target));

        VoidFacts facts = inPos(() -> ordersApi.voidFacts(voided.voidId()));
        assertThat(facts.lineSnapshot().lineId()).isEqualTo(copy);
        assertThat(facts.consumedLineId()).isEqualTo(line);
    }

    @Test
    void aVoidAfterTwoTransfersNamesTheOriginalRoundLine() throws Exception {
        UUID middle = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        UUID last = fixtures.openedAt(shop, fixtures.newTable(shop, "M3"), operator, terminal);
        UUID lastCopy = transferOne(middle, transferOne(order, line, middle), last);

        VoidView voided = voidOf(last, lastCopy, version(last));

        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).consumedLineId()).isEqualTo(line);
    }

    /** Transfers 1 of {@code sourceLine} to {@code target}; the copy's id on the target. */
    UUID transferOne(UUID source, UUID sourceLine, UUID target) throws Exception {
        fixtures.transfer(shop, source, version(source), target, version(target), Map.of(sourceLine, "1"), false,
                operator, terminal, UUID.randomUUID().toString()).andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();
    }

    long version(UUID orderId) {
        return inPos(() -> ordersApi.billingView(orderId)).version();
    }

    VoidView voidOf(UUID orderId, UUID lineId, long version) {
        return inPos(() -> ordersApi.voidLine(new SettledVoidCommand(orderId, version, lineId, BigDecimal.ONE,
                "wrong dish", false, operator), approver));
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
