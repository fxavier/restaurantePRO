package com.xavier.backend.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * r2-decisions §4 orders: both orders of a transfer are version-bumped in ascending id order, so A→B and B→A at once
 * never deadlock (PostgreSQL would abort one with 40P01, a 500): one transfer wins and the other finds a moved version
 * (409). The net quantity across both orders is the same as before.
 */
@OrdersModuleTest
class ConcurrentTransferTest {

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminalA = UUID.randomUUID();
    final UUID terminalB = UUID.randomUUID();

    @RepeatedTest(5)
    void transfersAToBAndBToAAtOnceDoNotDeadlock() throws Exception {
        Shop shop = fixtures.shop();
        UUID a = fixtures.openedAt(shop, shop.table(), employee, terminalA);
        UUID lineOfA = fixtures.confirmedLine(shop, a, "2", 0, employee, terminalA);
        UUID b = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), employee, terminalB);
        UUID lineOfB = fixtures.confirmedLine(shop, b, "3", 0, employee, terminalB);

        List<Integer> statuses = OrdersFixtures.race(
                () -> transfer(shop, a, b, lineOfA, terminalA),
                () -> transfer(shop, b, a, lineOfB, terminalB));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(fixtures.netQuantity(a, b)).isEqualByComparingTo("5");
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_transfer WHERE source_order_id IN (?, ?)", a, b))
                .isOne();
    }

    int transfer(Shop shop, UUID from, UUID to, UUID line, UUID terminal) throws Exception {
        return fixtures.transfer(shop, from, 1, to, 1, Map.of(line, "1"), false, employee, terminal,
                UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
    }
}
