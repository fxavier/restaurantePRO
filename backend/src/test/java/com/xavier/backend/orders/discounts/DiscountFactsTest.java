package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.DiscountFacts;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.SourceRef;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D100, D101: reporting pulls each discount source once: a round's promoted lines (the discount against the list
 * price) and each adjustment (the discount against the price before it), with origin, operator and approver; the
 * sources of a store and period come back in creation order.
 */
@OrdersModuleTest
class DiscountFactsTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aPromotedRoundAndAnAdjustmentAreTwoSourcesOfTheirDiscounts() throws Exception {
        UUID operator = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        Shop shop = fixtures.shop();
        promotions.promotion(shop.store(), shop.item(), OrdersPromotions.code("F"),
                "\"kind\": \"AMOUNT_OFF_UNIT\", \"amount\": 0.50", null);
        long promotionVersion = promotions.publish(shop.store());
        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        UUID line = id(fixtures.draft(shop, order, "2", operator, terminal));
        fixtures.confirm(shop, order, """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                .formatted(shop.catalogVersion(), line, promotionVersion), operator, terminal)
                .andExpect(status().isCreated());
        fixtures.discount(shop, order, """
                {"orderVersion": 1, "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "unitPrice": 11.50,
                 "reason": "regular"}""".formatted(line), operator, terminal, Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        UUID batch = jdbc.sql("SELECT batch_id FROM orders.order_line WHERE id = ?").param(line)
                .query(UUID.class).single();

        DiscountFacts round = ordersApi.discountFacts("BATCH", batch);
        assertThat(round.lines()).singleElement().satisfies(fact -> {
            assertThat(fact.origin()).isEqualTo("PROMOTION");
            assertThat(fact.listUnitPrice().amount()).isEqualByComparingTo("12.50");
            assertThat(fact.unitPrice().amount()).isEqualByComparingTo("12.00");
            assertThat(fact.discountGross().amount()).isEqualByComparingTo("1.00");
            assertThat(fact.operatorEmployeeId()).isEqualTo(operator);
        });
        var sources = ordersApi.discountSourceIds(shop.store(), LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1),
                PageRequest.of(0, 10));
        assertThat(sources.getContent()).extracting(SourceRef::sourceType).containsExactly("BATCH", "ADJUSTMENT");
        DiscountFacts adjusted = ordersApi.discountFacts("ADJUSTMENT", sources.getContent().get(1).sourceId());
        assertThat(adjusted.lines()).singleElement().satisfies(fact -> {
            assertThat(fact.origin()).isEqualTo("MANUAL");
            assertThat(fact.previousUnitPrice().amount()).isEqualByComparingTo("12.00");
            assertThat(fact.unitPrice().amount()).isEqualByComparingTo("11.50");
            assertThat(fact.quantity()).isEqualByComparingTo("2");
            assertThat(fact.discountGross().amount()).isEqualByComparingTo("1.00");
        });
    }
}
