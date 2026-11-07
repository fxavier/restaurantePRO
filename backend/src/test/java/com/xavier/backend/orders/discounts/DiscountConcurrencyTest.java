package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D105, D133: two terminals discounting the same line at once both claim the order's version; one wins, the other
 * gets 409 and records nothing, so the line has exactly one adjustment and the limit is never checked against a
 * stale price. Two threads released together by a latch, real PostgreSQL.
 */
@OrdersModuleTest
class DiscountConcurrencyTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Test
    void twoTerminalsDiscountingOneLineAtOnceRecordOneAdjustment() throws Exception {
        UUID operator = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        UUID line = fixtures.confirmedLine(shop, order, "2", 0, operator, terminal);
        promotions.publish(shop.store()); // the default limits, 10 % free (D106)
        String body = """
                {"orderVersion": 1, "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "unitPrice": 12.00,
                 "reason": "regular"}""".formatted(line);

        List<Integer> statuses = OrdersFixtures.race(
                () -> fixtures.discount(shop, order, body, UUID.randomUUID(), UUID.randomUUID(),
                        Permission.ORDER_DISCOUNT, null).andReturn().getResponse().getStatus(),
                () -> fixtures.discount(shop, order, body, UUID.randomUUID(), UUID.randomUUID(),
                        Permission.ORDER_DISCOUNT, null).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line_price_adjustment WHERE line_id = ?", line))
                .isOne();
        assertThat(fixtures.count("SELECT version FROM orders.order_header WHERE id = ?", order)).isEqualTo(2);
    }
}
