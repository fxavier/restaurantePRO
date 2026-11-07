package com.xavier.backend.inventory.consumption.flow;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.json;
import static com.xavier.backend.inventory.consumption.flow.LiveStore.roundPublications;
import static com.xavier.backend.inventory.consumption.flow.LiveStore.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementTestClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D116, A44 (the held half), §7: the order transaction never touches inventory. With the round's consumption held,
 * the round, the payment and the close all commit, and the backlog shows as {@code pendingFacts}; released, the round
 * is consumed. The round confirmed before go-live is never consumed (D122).
 */
@ConsumptionFlowTest
class SaleNotBlockedByInventoryTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    InventoryFixtures inventory;

    @Autowired
    RoundConsumption consumption;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theRoundPaymentAndCloseCommitWhileConsumptionIsHeld() throws Exception {
        Sale sale = sales.sale("1");
        waitUntil(() -> roundPublications(jdbc, sale.order(), true) == 1, "the first round's consumption");
        LiveStore live = LiveStore.of(sale, mvc, inventory);
        RoundConsumption held = AopTestUtils.getUltimateTargetObject(consumption);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("never released");
            }
            return call.callRealMethod();
        }).when(held).consume(any());
        try {
            sales.confirmRound(sale.shop(), sale.cashier(), sale.order(), "1",
                    sales.orderVersion(sale.cashier(), sale.order()));
            assertThat(entered.await(60, TimeUnit.SECONDS)).as("consumption started").isTrue();

            sales.payAndClose(sale);

            assertThat(json(mvc.perform(get(BO + "/status").param("storeId", live.store().toString())
                            .with(account(live.store(), Permission.INVENTORY_VIEW))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("pendingFacts").asLong()).isPositive();
            assertThat(live.movements(jdbc, "SALE_CONSUMPTION")).isZero();
        } finally {
            release.countDown();
        }
        waitUntil(() -> live.movements(jdbc, "SALE_CONSUMPTION") == 1, "the held round consumed");
        reset(held);
        assertThat(live.flourInStock(jdbc)).isEqualByComparingTo("99.75");
    }
}
