package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersTestClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The POS table map's order side (light reference, 2026-11-23): for every seated OPEN order of the session's store,
 * its server total and when its last round was sent, plus the threshold after which a table has gone too long
 * without a round. Dining cannot read orders (orders depends on dining), so the POS merges this with the map by order.
 */
@OrdersModuleTest
class TableActivityHttpTest {

    static final Instant OPENED = Instant.parse("2026-11-20T12:00:00Z");
    static final Instant FIRST_ROUND = Instant.parse("2026-11-20T12:10:00Z");
    static final Instant SECOND_ROUND = Instant.parse("2026-11-20T12:25:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersTestClock clock;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void aShop() throws Exception {
        shop = fixtures.shop();
    }

    @AfterEach
    void releaseTheClock() {
        clock.release();
    }

    @Test
    void aSeatedOrderShowsItsServerTotalAndWhenItsLastRoundWasSent() throws Exception {
        clock.pin(OPENED);
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        clock.pin(FIRST_ROUND);
        fixtures.confirmedLine(shop, order, "2", 0, employee, terminal);
        clock.pin(SECOND_ROUND);
        fixtures.confirmedLine(shop, order, "1", 1, employee, terminal);
        fixtures.draft(shop, order, "3", employee, terminal);

        activity(shop.store())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueAfterMinutes").value(30))
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].tableId".formatted(order))
                        .value(contains(shop.table().toString())))
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].total.amount".formatted(order))
                        .value(contains("37.50")))
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].total.currency".formatted(order))
                        .value(contains("MZN")))
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].lastRoundAt".formatted(order))
                        .value(contains("2026-11-20T12:25:00Z")));
    }

    @Test
    void anOrderWithoutARoundHasAZeroTotalAndNoLastRound() throws Exception {
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        fixtures.draft(shop, order, "1", employee, terminal);

        activity(shop.store())
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].total.amount".formatted(order))
                        .value(contains("0.00")))
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')].lastRoundAt".formatted(order))
                        .value(contains((Object) null)));
    }

    @Test
    void onlyTheSessionStoresTablesAreListed() throws Exception {
        UUID order = id(fixtures.openedOrder(shop, employee, terminal));
        Shop other = fixtures.shop();

        activity(other.store())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.orderId == '%s')]".formatted(order)).isEmpty());
    }

    @Test
    void readingNeedsOrderView() throws Exception {
        mvc.perform(get(POS + "/orders/table-activity")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
    }

    ResultActions activity(UUID store) throws Exception {
        return mvc.perform(get(POS + "/orders/table-activity")
                .with(OrdersActors.pos(store, employee, terminal, Permission.ORDER_VIEW)));
    }
}
