package com.xavier.backend.orders.pricing;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.OrdersTestClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D103, D104: a happy hour is read in the store's zone at the confirmation instant, not when the draft was typed. A
 * window that ends between the preview and the tap makes the POS re-read ({@code PRICES_CHANGED}); confirmed inside
 * the window, the line keeps the happy-hour price. The clock is pinned near 20:00 Maputo, far from the 05:00 cutoff.
 */
@OrdersModuleTest
class HappyHourBoundaryTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersTestClock clock;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    long promotionVersion;
    Instant lastSecond;

    @BeforeEach
    void aHappyHourFromSixToEight() throws Exception {
        shop = fixtures.shop();
        promotions.promotion(shop.store(), shop.item(), OrdersPromotions.code("H"),
                "\"kind\": \"PERCENT_OFF\", \"percent\": 20", """
                [{"daysOfWeek": [1, 2, 3, 4, 5, 6, 7], "start": "18:00", "end": "20:00"}]""");
        promotionVersion = promotions.publish(shop.store());
        order = id(fixtures.openedOrder(shop, employee, terminal));
        lastSecond = LocalDate.now(MAPUTO).plusDays(1).atTime(LocalTime.of(19, 59, 59)).atZone(MAPUTO).toInstant();
    }

    @AfterEach
    void releaseTheClock() {
        clock.release();
    }

    @Test
    void theWindowEndingBetweenPreviewAndConfirmationMakesThePosReRead() throws Exception {
        clock.pin(lastSecond);
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));
        assertThat(fixtures.order(shop, order).get("lines").get(0).get("unitPrice").get("amount").decimalValue())
                .isEqualByComparingTo("10.00");

        clock.pin(lastSecond.plusSeconds(1));
        fixtures.confirm(shop, order, body(line, "10.00"), employee, terminal)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICES_CHANGED"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(12.50));

        fixtures.confirm(shop, order, body(line, "12.50"), employee, terminal).andExpect(status().isCreated());
        assertThat(ordersApi.billingView(order).due().amount()).isEqualByComparingTo("12.50");
    }

    @Test
    void confirmedInsideTheWindowTheLineKeepsTheHappyHourPrice() throws Exception {
        clock.pin(lastSecond);
        UUID line = id(fixtures.draft(shop, order, "1", employee, terminal));

        fixtures.confirm(shop, order, body(line, "10.00"), employee, terminal).andExpect(status().isCreated());
        clock.pin(lastSecond.plusSeconds(3600));

        assertThat(ordersApi.billingView(order).lines().getFirst().unitPrice().amount())
                .isEqualByComparingTo("10.00");
    }

    String body(UUID line, String expectedGross) {
        return """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d,
                 "expectedGross": %s}""".formatted(shop.catalogVersion(), line, promotionVersion, expectedGross);
    }
}
