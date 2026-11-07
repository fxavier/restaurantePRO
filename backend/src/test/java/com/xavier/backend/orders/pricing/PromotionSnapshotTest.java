package com.xavier.backend.orders.pricing;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.promotions.PromotionsFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §14 #7, D102: a confirmed line keeps the promotion it was priced with. Editing the promotion and republishing
 * changes the next round only; the earlier line still bills at its price and names the old promotion.
 */
@OrdersModuleTest
class PromotionSnapshotTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    PromotionsFixtures promotionsFixtures;

    @Autowired
    OrdersApi ordersApi;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void republishingNeverRepricesAConfirmedLine() throws Exception {
        Shop shop = fixtures.shop();
        String code = OrdersPromotions.code("S");
        JsonNode promotion = promotions.promotion(shop.store(), shop.item(), code,
                "\"kind\": \"PERCENT_OFF\", \"percent\": 20", null);
        long first = promotions.publish(shop.store());
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        UUID before = confirmOne(shop, order, 0, first);

        PromotionsFixtures.expect(promotionsFixtures.perform(put(PromotionsFixtures.PROMOTIONS + "/"
                        + PromotionsFixtures.id(promotion)).with(admin(Permission.PROMOTION_MANAGE)), """
                {"name": "Renamed", "kind": "PERCENT_OFF", "percent": 50, "priority": 1, "membersOnly": false,
                 "active": true, "version": %d}""".formatted(promotion.get("version").asLong())), 200);
        long second = promotions.publish(shop.store());
        UUID after = confirmOne(shop, order, 1, second);

        BillingLine old = line(order, before);
        assertThat(old.unitPrice().amount()).isEqualByComparingTo("10.00");
        assertThat(old.discountLabel()).isEqualTo("Promo " + code);
        BillingLine next = line(order, after);
        assertThat(next.unitPrice().amount()).isEqualByComparingTo("6.25");
        assertThat(next.discountLabel()).isEqualTo("Renamed");
    }

    UUID confirmOne(Shop shop, UUID order, long orderVersion, long promotionVersion) throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        fixtures.confirm(shop, order, """
                {"orderVersion": %d, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                .formatted(orderVersion, shop.catalogVersion(), line, promotionVersion), employee, terminal)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return line;
    }

    BillingLine line(UUID order, UUID line) {
        return ordersApi.billingView(order).lines().stream().filter(billed -> billed.lineId().equals(line))
                .findFirst().orElseThrow();
    }
}
