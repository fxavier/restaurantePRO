package com.xavier.backend.orders.pricing;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D104: publishing promotions between the preview and the confirmation is a conflict like a catalog change: the POS
 * gets the new version, re-reads its previews and confirms again at the new prices.
 */
@OrdersModuleTest
class PromotionRepublishTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void aReleasePublishedAfterThePreviewIsAConflictUntilThePosReReads() throws Exception {
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        promotions.promotion(shop.store(), shop.item(), OrdersPromotions.code("R"),
                "\"kind\": \"FIXED_UNIT_PRICE\", \"amount\": 9.00", null);
        long published = promotions.publish(shop.store());

        fixtures.confirm(shop, order, body(shop, line, null, "12.50"), employee, terminal)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTIONS_CHANGED"))
                .andExpect(jsonPath("$.promotionVersion").value(published));

        fixtures.confirm(shop, order, body(shop, line, published, "9.00"), employee, terminal)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(9.00));
    }

    static String body(Shop shop, UUID line, Long promotionVersion, String expectedGross) {
        return """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %s,
                 "expectedGross": %s}""".formatted(shop.catalogVersion(), line, promotionVersion, expectedGross);
    }
}
