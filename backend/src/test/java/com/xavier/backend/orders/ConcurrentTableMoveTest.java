package com.xavier.backend.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A table move and a table opening take their locks in one order (the order row and its open-table index entry
 * first, then dining's table row), so a move to a table racing an opening of it never deadlocks (that would be a
 * 500): one of them gets the table, the other 409 {@code TABLE_OCCUPIED}.
 */
@OrdersModuleTest
class ConcurrentTableMoveTest {

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminalA = UUID.randomUUID();
    final UUID terminalB = UUID.randomUUID();

    @RepeatedTest(5)
    void aMoveToATableRacingItsOpeningGivesItToOneOfThem() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = fixtures.openedAt(shop, shop.table(), employee, terminalA);
        UUID contested = fixtures.newTable(shop, "M9");

        List<Integer> statuses = OrdersFixtures.race(
                () -> fixtures.moveTable(shop, order, 0, contested, employee, terminalA, UUID.randomUUID().toString())
                        .andReturn().getResponse().getStatus(),
                () -> fixtures.openTable(shop, contested, employee, terminalB, UUID.randomUUID().toString())
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).hasSize(2).containsAnyOf(200, 201).allMatch(s -> s == 200 || s == 201 || s == 409);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE table_id = ? AND closed_at IS NULL""", contested))
                .isOne();
    }
}
