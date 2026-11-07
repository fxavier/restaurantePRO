package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.SettledVoidCommand;
import com.xavier.backend.orders.VoidView;
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
 * D105: a discount on part of a line splits it first. The new line holds the quantity at the new price, has no round
 * (production gets no second ticket, stock no second consumption) and the source keeps the rest; the net quantity of
 * the order does not change. A void or a transfer of the split line traces back to the round line it came from.
 */
@OrdersModuleTest
class LineSplitTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;
    UUID batch;

    @BeforeEach
    void aRoundOfThreeCoffees() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        batch = jdbc.sql("SELECT batch_id FROM orders.order_line WHERE id = ?").param(line).query(UUID.class).single();
        promotions.publish(shop.store()); // the default limits, 10 % free (D106)
    }

    @Test
    void discountingOneOfThreeSplitsItOffWithoutASecondRound() throws Exception {
        fixtures.discount(shop, order, """
                        {"orderVersion": 1, "lines": [{"lineId": "%s", "quantity": 1}], "kind": "DISCOUNT",
                         "unitPrice": 12.00, "reason": "regular"}""".formatted(line), operator, terminal,
                        Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(37.00));

        OrderBillingView bill = ordersApi.billingView(order);
        assertThat(bill.lines()).extracting(billed -> billed.netQty().stripTrailingZeros().toPlainString() + "@"
                        + billed.unitPrice().amount().toPlainString())
                .containsExactly("2@12.50", "1@12.00");
        assertThat(fixtures.netQuantity(order)).isEqualByComparingTo("3");
        UUID split = splitLine();
        assertThat(jdbc.sql("SELECT batch_id IS NULL AND origin_split_id IS NOT NULL FROM orders.order_line "
                + "WHERE id = ?").param(split).query(Boolean.class).single()).isTrue();
        BatchFacts facts = ordersApi.batchFacts(batch);
        assertThat(facts.lines()).singleElement().satisfies(fact -> {
            assertThat(fact.lineId()).isEqualTo(line);
            assertThat(fact.quantity()).isEqualByComparingTo("3");
        });
    }

    @Test
    void moreThanTheNetQuantityIsRefused() throws Exception {
        fixtures.discount(shop, order, """
                        {"orderVersion": 1, "lines": [{"lineId": "%s", "quantity": 4}], "kind": "DISCOUNT",
                         "percent": 5, "reason": "regular"}""".formatted(line), operator, terminal,
                        Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_NET"));
    }

    @Test
    void aVoidOfASplitLineNamesTheRoundLineItCameFrom() throws Exception {
        splitOne();
        UUID split = splitLine();

        VoidView voided = inPos(() -> ordersApi.voidLine(new SettledVoidCommand(order, version(order), split,
                BigDecimal.ONE, "wrong dish", false, operator), UUID.randomUUID()));

        assertThat(voided.gross().amount()).isEqualByComparingTo("12.00");
        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).consumedLineId()).isEqualTo(line);
        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).gross().amount()).isEqualByComparingTo("12.00");
    }

    @Test
    void aTransferredSplitLineKeepsItsPriceAndTracesBackToTheRoundLine() throws Exception {
        splitOne();
        UUID split = splitLine();
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        fixtures.transfer(shop, order, version(order), target, version(target), Map.of(split, "1"), false,
                operator, terminal, UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID copy = jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();

        BillingLine moved = ordersApi.billingView(target).lines().getFirst();
        assertThat(moved.unitPrice().amount()).isEqualByComparingTo("12.00");
        assertThat(moved.listUnitPrice().amount()).isEqualByComparingTo("12.50");
        VoidView voided = inPos(() -> ordersApi.voidLine(new SettledVoidCommand(target, version(target), copy,
                BigDecimal.ONE, "wrong dish", false, operator), UUID.randomUUID()));
        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).consumedLineId()).isEqualTo(line);
    }

    void splitOne() throws Exception {
        fixtures.discount(shop, order, """
                        {"orderVersion": 1, "lines": [{"lineId": "%s", "quantity": 1}], "kind": "DISCOUNT",
                         "unitPrice": 12.00, "reason": "regular"}""".formatted(line), operator, terminal,
                        Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
    }

    UUID splitLine() {
        return jdbc.sql("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?").param(line)
                .query(UUID.class).single();
    }

    long version(UUID orderId) {
        return ordersApi.billingView(orderId).version();
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
