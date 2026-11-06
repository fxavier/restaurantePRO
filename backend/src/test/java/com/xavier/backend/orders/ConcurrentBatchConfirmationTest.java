package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A2 (D50 §7.1): two terminals confirm a round on the same order at the same instant → exactly one new batch, one
 * 409, and no draft lost; the same Idempotency-Key twice at once → still one batch.
 */
@OrdersModuleTest
class ConcurrentBatchConfirmationTest {

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminalA = UUID.randomUUID();
    final UUID terminalB = UUID.randomUUID();

    @RepeatedTest(3)
    void twoTerminalsConfirmingTheirOwnDraftsAtOnceGetOneBatchAndOneConflictAndLoseNoDraft() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, employee, terminalA));
        UUID lineA = id(fixtures.draft(shop, order, "1", employee, terminalA));
        UUID lineB = id(fixtures.draft(shop, order, "2", employee, terminalB));

        List<Integer> statuses = OrdersFixtures.race(
                () -> confirm(shop, order, List.of(lineA), terminalA, UUID.randomUUID().toString()),
                () -> confirm(shop, order, List.of(lineB), terminalB, UUID.randomUUID().toString()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(batches(order)).isOne();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line WHERE order_id = ? AND status = 'DRAFT'",
                order)).isOne();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line WHERE order_id = ?", order)).isEqualTo(2);
    }

    @RepeatedTest(3)
    void twoTerminalsConfirmingTheSameDraftsAtOnceGetOneBatch() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, employee, terminalA));
        List<UUID> lines = List.of(id(fixtures.draft(shop, order, "1", employee, terminalA)),
                id(fixtures.draft(shop, order, "3", employee, terminalA)));

        List<Integer> statuses = OrdersFixtures.race(
                () -> confirm(shop, order, lines, terminalA, UUID.randomUUID().toString()),
                () -> confirm(shop, order, lines, terminalB, UUID.randomUUID().toString()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(batches(order)).isOne();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line WHERE order_id = ? AND batch_id IS NOT NULL",
                order)).isEqualTo(2);
    }

    @Test
    void theSameKeyTwiceAtOnceMakesOneBatch() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, employee, terminalA));
        List<UUID> lines = List.of(id(fixtures.draft(shop, order, "1", employee, terminalA)));
        String key = UUID.randomUUID().toString();

        List<Integer> statuses = OrdersFixtures.race(
                () -> confirm(shop, order, lines, terminalA, key),
                () -> confirm(shop, order, lines, terminalA, key));

        assertThat(statuses).contains(201).allMatch(status -> status == 201 || status == 409);
        assertThat(batches(order)).isOne();
    }

    int confirm(Shop shop, UUID order, List<UUID> lines, UUID terminal, String key) throws Exception {
        return fixtures.confirm(shop, order, 0, shop.catalogVersion(), lines, employee, terminal, key)
                .andReturn().getResponse().getStatus();
    }

    long batches(UUID order) {
        return fixtures.count("SELECT count(*) FROM orders.order_batch WHERE order_id = ?", order);
    }
}
