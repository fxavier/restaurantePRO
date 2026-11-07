package com.xavier.backend.orders.pricing;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D101–D104: a round is priced by the store's published promotions at the confirmation instant. The line keeps its
 * list price, its effective price and the promotion that set it; the bill uses the effective price; the POS must have
 * previewed the published release ({@code PROMOTIONS_CHANGED}) and, when it says so, the same gross
 * ({@code PRICES_CHANGED}). A38 (orders half).
 */
@OrdersModuleTest
class ConfirmWithPromotionTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrderEventProbe events;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    long promotionVersion;

    @BeforeEach
    void aShopWithTwentyPercentOffItsItem() throws Exception {
        shop = fixtures.shop();
        promotions.promotion(shop.store(), shop.item(), OrdersPromotions.code("P"),
                "\"kind\": \"PERCENT_OFF\", \"percent\": 20", null);
        promotionVersion = promotions.publish(shop.store());
        order = id(fixtures.openedOrder(shop, employee, terminal));
    }

    @Test
    void aDraftPreviewsTheEffectivePriceNextToTheListPrice() throws Exception {
        JsonNode draft = fixtures.draft(shop, order, "2", employee, terminal);

        assertThat(draft.get("listUnitPrice").get("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(draft.get("unitPrice").get("amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(draft.get("gross").get("amount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(draft.get("origin").asString()).isEqualTo("PROMOTION");
        assertThat(fixtures.order(shop, order).get("promotionVersion").asLong()).isEqualTo(promotionVersion);
    }

    @Test
    void confirmationSnapshotsListPriceEffectivePriceAndThePromotion() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "2", employee, terminal));

        fixtures.confirm(shop, order, body(line, promotionVersion, "20.00"), employee, terminal)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(10.00))
                .andExpect(jsonPath("$.lines[0].listUnitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].discount.amount").value(5.00))
                .andExpect(jsonPath("$.lines[0].origin").value("PROMOTION"))
                .andExpect(jsonPath("$.lines[0].promotionCode").isNotEmpty());

        OrderBillingView bill = ordersApi.billingView(order);
        assertThat(bill.due().amount()).isEqualByComparingTo("20.00");
        assertThat(bill.lines().getFirst().unitPrice().amount()).isEqualByComparingTo("10.00");
        assertThat(bill.lines().getFirst().listUnitPrice().amount()).isEqualByComparingTo("12.50");
        assertThat(bill.lines().getFirst().discountLabel()).startsWith("Promo ");
        assertThat(events.discountsOf(order, 1)).singleElement()
                .satisfies(event -> assertThat(event.sourceType()).isEqualTo(LineDiscountsRecorded.BATCH));
    }

    @Test
    void aRoundPricedWithoutThePublishedPromotionsIsAConflict() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        fixtures.confirm(shop, order, body(line, null, null), employee, terminal)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTIONS_CHANGED"))
                .andExpect(jsonPath("$.promotionVersion").value(promotionVersion));
        assertThat(fixtures.order(shop, order).get("lines").get(0).get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void anotherGrossThanThePosShowedIsAConflictWithTheNewPreview() throws Exception {
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        fixtures.confirm(shop, order, body(line, promotionVersion, "12.50"), employee, terminal)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICES_CHANGED"))
                .andExpect(jsonPath("$.lines[0].lineId").value(line.toString()))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(10.00));
    }

    String body(UUID line, Long promotionVersion, String expectedGross) {
        return """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %s,
                 "expectedGross": %s}""".formatted(shop.catalogVersion(), line, promotionVersion, expectedGross);
    }
}
