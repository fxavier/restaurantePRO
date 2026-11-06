package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.shared.exception.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The facts of one round (D100), which inventory consumes: its own lines, at their confirmed snapshot. */
@OrdersModuleTest
class BatchFactsTest {

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenTable() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
    }

    @Test
    void aTwoLineRoundHasBothLinesAtTheirConfirmedSnapshot() throws Exception {
        UUID first = id(fixtures.draft(shop, order, "2", operator, terminal));
        UUID second = id(fixtures.draft(shop, order, "1", operator, terminal));
        fixtures.confirm(shop, order, 0, shop.catalogVersion(), List.of(first, second), operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID batch = batchOf(first);

        BatchFacts facts = ordersApi.batchFacts(batch);

        assertThat(facts.batchId()).isEqualTo(batch);
        assertThat(facts.orderId()).isEqualTo(order);
        assertThat(facts.storeId()).isEqualTo(shop.store());
        assertThat(facts.businessDay()).isEqualTo(jdbc.sql(
                "SELECT business_day FROM orders.order_header WHERE id = ?").param(order).query(LocalDate.class)
                .single());
        assertThat(facts.mode()).isEqualTo(OrderMode.DINE_IN);
        assertThat(facts.confirmedAt()).isEqualTo(jdbc.sql(
                "SELECT confirmed_at FROM orders.order_batch WHERE id = ?").param(batch).query(Instant.class)
                .single());
        assertThat(facts.lines()).extracting(BatchLineFact::lineId).containsExactly(first, second);
        BatchLineFact line = facts.lines().getFirst();
        assertThat(line.itemId()).isEqualTo(shop.item());
        assertThat(line.variantId()).isNull();
        assertThat(line.releaseId()).hasToString(column(first, "release_id"));
        assertThat(line.quantity()).isEqualByComparingTo("2");
        assertThat(line.destination()).isEqualTo(column(first, "destination"));
        assertThat(line.components()).isEmpty();
        assertThat(facts.lines().get(1).quantity()).isEqualByComparingTo("1");
    }

    @Test
    void aLineTransferredInIsNotInTheTargetsRoundAndTheSourceRoundKeepsItsLine() throws Exception {
        UUID moved = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        UUID own = fixtures.confirmedLine(shop, target, "1", 0, operator, terminal);
        fixtures.transfer(shop, order, 1, target, 1, Map.of(moved, "2"), false, operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        assertThat(ordersApi.batchFacts(batchOf(own)).lines()).extracting(BatchLineFact::lineId)
                .containsExactly(own);
        assertThat(ordersApi.batchFacts(batchOf(moved)).lines()).singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualByComparingTo("3"));
    }

    @Test
    void anUnknownRoundIsNotFound() {
        assertThatThrownBy(() -> ordersApi.batchFacts(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    UUID batchOf(UUID line) {
        return jdbc.sql("SELECT batch_id FROM orders.order_line WHERE id = ?").param(line).query(UUID.class).single();
    }

    String column(UUID line, String name) {
        return jdbc.sql("SELECT " + name + "::text FROM orders.order_line WHERE id = ?").param(line)
                .query(String.class).single();
    }
}
