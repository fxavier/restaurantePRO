package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.reporting.CostKitchen.LiveShop;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D86, D131: the cost reports are only as current as consumption. A round inventory has not consumed yet is pending
 * in the margin and discount reports, and a margin report is not final while the store has an open consumption
 * exception confirmed in its range: that cost is missing until the exception is reprocessed.
 */
@ReportingModuleTest
class CostFreshnessTest {

    @Autowired
    CostKitchen kitchen;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    CashFixtures cash;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    RoundConsumption consumption;

    @Autowired
    MockMvc mvc;

    @Test
    void aRoundNotConsumedYetIsPendingInTheCostReports() throws Exception {
        LiveShop live = kitchen.liveShop();
        kitchen.recipe(live, live.shop().item(), "250");
        Sale sale = kitchen.openSale(live.shop());
        LocalDate day = reporting.businessDayOf(sale.order());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            held.countDown();
            release.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(consumption).consume(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            // the listener may run on the confirming thread after its commit, so the round is sent from another one
            Future<?> confirmed = pool.submit(() -> {
                sales.confirmRound(live.shop(), sale.cashier(), sale.order(), "1", 0);
                return null;
            });
            assertThat(held.await(20, TimeUnit.SECONDS)).as("the round's consumption started").isTrue();

            for (String[] report : new String[][] {{"margins", "item"}, {"discounts", "origin"}}) {
                JsonNode whileHeld = reporting.report(report[0], live.shop().store(), day, day, "groupBy",
                        report[1]);
                assertThat(whileHeld.get("pendingFacts").asLong()).as(report[0]).isPositive();
                assertThat(whileHeld.get("final").asBoolean()).as(report[0]).isFalse();
            }

            release.countDown();
            confirmed.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        kitchen.awaitRows("consumption_record", live.shop().store(), 1);
    }

    @Test
    void anOpenConsumptionExceptionKeepsTheMarginsNotFinalUntilItIsReprocessed() throws Exception {
        LiveShop live = kitchen.liveShop(); // no recipe yet: the round raises an exception
        Sale sale = kitchen.openSale(live.shop());
        sales.confirmRound(live.shop(), sale.cashier(), sale.order(), "2", 0);
        UUID store = live.shop().store();
        ReportingFixtures.await("the consumption exception", () -> reporting.count(
                "SELECT count(*) FROM inventory.consumption_exception WHERE store_id = ? AND resolved_at IS NULL",
                store) == 1);
        assertThat(reporting.row("SELECT business_day FROM inventory.consumption_exception WHERE store_id = ?", store)
                .get("business_day")).as("the order's business day").isEqualTo(java.sql.Date.valueOf(
                reporting.businessDayOf(sale.order())));
        sales.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        closeTheDayFinancially(sale);
        reporting.issuedDocuments(sale.order()).forEach(reporting::awaitSalesRecord);
        LocalDate day = reporting.businessDayOf(sale.order());
        ReportingFixtures.await("nothing pending", () -> margins(store, day).get("pendingFacts").asLong() == 0);

        assertThat(margins(store, day).get("final").asBoolean()).as("the round's cost is missing").isFalse();
        assertThat(reporting.report("sales", store, day, day, "groupBy", "day").get("final").asBoolean())
                .as("the sales report does not depend on consumption").isTrue();

        kitchen.recipe(live, live.shop().item(), "250");
        UUID exception = (UUID) reporting.row("SELECT id FROM inventory.consumption_exception WHERE store_id = ?",
                store).get("id");
        mvc.perform(InventoryFixtures.idem(post(InventoryFixtures.BO + "/consumption-exceptions/" + exception
                        + "/reprocess")).with(InventoryFixtures.account(store, Permission.INVENTORY_REBUILD)))
                .andExpect(status().isOk());
        kitchen.awaitRows("consumption_record", store, 1);
        ReportingFixtures.await("nothing pending", () -> margins(store, day).get("pendingFacts").asLong() == 0);

        assertThat(margins(store, day).get("final").asBoolean()).as("after the reprocess").isTrue();
    }

    private JsonNode margins(UUID store, LocalDate day) {
        try {
            return reporting.report("margins", store, day, day, "groupBy", "item");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void closeTheDayFinancially(Sale sale) throws Exception {
        sales.close(sale.cashier(), sale.order(), sales.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        sales.closeSession(sale, "25.00");
        JsonNode day = sales.currentDay(sale.cashier());
        sales.closeDayAs(sale.cashier(), day).andExpect(status().isOk());
        UUID dayId = UUID.fromString(day.get("id").asString());
        cash.approveClosing(sale.store(), cash.submittedClosing(sale.cashier(), sale.register(), dayId));
        cash.closeDayFinancially(sale.store(), dayId);
    }
}
