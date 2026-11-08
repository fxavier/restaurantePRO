package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.reporting.CostKitchen.LiveSale;
import com.xavier.backend.reporting.service.CostProjections;
import com.xavier.backend.settlement.SettlementFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D131, A52: the margin of a seeded day is net sales − consumption cost, per item, with waste apart. The day: 4 items
 * sold (12.50 at 16 %, 250 g of flour at 2.00 = 0.50 each, so 2.00 consumed); one voided with waste (its 0.50 moves
 * from cost of sales to waste, no stock moves) and one voided without (its 0.50 is reversed); the other two paid
 * (net 21.55). Then 1 kg of flour wasted by hand (2.00, no item sold) and a linked supplier return whose credit is
 * above the average (a cost variance of −5.00). Cost of sales is 1.00, as inventory's own rotation report says.
 */
@ReportingModuleTest
class MarginReportTest {

    @Autowired
    CostKitchen kitchen;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    CostProjections projections;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    LiveSale live;
    LocalDate day;

    @BeforeEach
    void aSeededDay() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        live = kitchen.liveSale("4");
        UUID store = live.store();
        kitchen.awaitRows("consumption_record", store, 1);
        UUID line = sales.lineIds(live.sale().cashier(), live.sale().order()).getFirst();
        kitchen.voidLine(live.sale(), line, "1", true).andExpect(status().isCreated());
        kitchen.voidLine(live.sale(), line, "1", false).andExpect(status().isCreated());
        sales.payAndClose(live.sale());
        kitchen.wasteFlour(live, "1");
        kitchen.linkedReturnWithVariance(live);
        reporting.issuedDocuments(live.sale().order()).forEach(reporting::awaitSalesRecord);
        kitchen.awaitRows("consumption_record", store, 3); // the sale, the reversal, the manual waste
        kitchen.awaitRows("consumption_waste_record", store, 1);
        kitchen.awaitRows("cost_variance_record", store, 1);
        reporting.awaitNothingPendingFor(store);
        day = reporting.businessDayOf(live.sale().order());
    }

    @Test
    void theMarginPerItemIsNetSalesLessTheConsumptionCostWithWasteApart() throws Exception {
        JsonNode report = reporting.report("margins", live.store(), day, day, "groupBy", "item");

        JsonNode item = report.get("rows").get(0);
        assertThat(item.get("key").asString()).isEqualTo(live.sale().shop().item().toString());
        assertThat(item.get("label").asString()).isNotBlank();
        assertThat(amount(item, "netSales")).isEqualTo("21.55");
        assertThat(amount(item, "costOfSales")).as("2.00 consumed, 0.50 reversed, 0.50 to waste").isEqualTo("1.00");
        assertThat(amount(item, "waste")).isEqualTo("0.50");
        assertThat(amount(item, "costVariance")).isEqualTo("0.00");
        assertThat(amount(item, "margin")).isEqualTo("20.55");
        assertThat(item.get("marginPercent").decimalValue()).isEqualByComparingTo("95.36");

        JsonNode unsold = report.get("rows").get(1);
        assertThat(unsold.get("key").isNull()).as("waste and variances of no item sold").isTrue();
        assertThat(amount(unsold, "netSales")).isEqualTo("0.00");
        assertThat(amount(unsold, "costOfSales")).isEqualTo("0.00");
        assertThat(amount(unsold, "waste")).isEqualTo("2.00");
        assertThat(amount(unsold, "costVariance")).isEqualTo("-5.00");
        assertThat(amount(unsold, "margin")).isEqualTo("5.00");
        assertThat(unsold.get("marginPercent").isNull()).isTrue();
        assertThat(report.get("rows")).hasSize(2);

        JsonNode totals = report.get("totals");
        assertThat(amount(totals, "netSales")).isEqualTo("21.55");
        assertThat(amount(totals, "costOfSales")).isEqualTo("1.00");
        assertThat(amount(totals, "waste")).isEqualTo("2.50");
        assertThat(amount(totals, "costVariance")).isEqualTo("-5.00");
        assertThat(amount(totals, "margin")).isEqualTo("25.55");
        assertThat(report.get("asOf").isNull()).isFalse();
        assertThat(report.get("pendingFacts").isNumber()).isTrue();
        assertThat(report.get("final").asBoolean()).as("the day is still open").isFalse();
    }

    @Test
    void costOfSalesMirrorsInventorysRotationOfTheFlour() throws Exception {
        JsonNode rotation = InventoryFixtures.json(mvc.perform(get(InventoryFixtures.BO + "/reports/rotation")
                        .param("storeId", live.store().toString()).param("from", day.toString())
                        .param("to", day.toString())
                        .with(InventoryFixtures.account(live.store(), Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode flour = null;
        for (JsonNode row : rotation.get("rows")) {
            if (row.get("stockItemId").asString().equals(live.flour().toString())) {
                flour = row;
            }
        }

        assertThat(flour).isNotNull();
        assertThat(flour.get("consumptionCost").decimalValue()).isEqualByComparingTo(
                reporting.report("margins", live.store(), day, day, "groupBy", "item").get("totals")
                        .get("costOfSales").get("amount").decimalValue());
    }

    @Test
    void byFamilyTheCostIsTheFamilyOfTheReleaseTheLineWasSoldUnder() throws Exception {
        String family = (String) reporting.row("""
                SELECT family_code FROM reporting.sales_record_line WHERE store_id = ? LIMIT 1""", live.store())
                .get("family_code");

        JsonNode report = reporting.report("margins", live.store(), day, day, "groupBy", "family");

        assertThat(family).isNotNull();
        JsonNode row = report.get("rows").get(0);
        assertThat(row.get("key").asString()).isEqualTo(family);
        assertThat(amount(row, "netSales")).isEqualTo("21.55");
        assertThat(amount(row, "costOfSales")).isEqualTo("1.00");
        assertThat(amount(row, "waste")).isEqualTo("0.50");
        assertThat(amount(report.get("rows").get(1), "costVariance")).isEqualTo("-5.00");
    }

    @Test
    void byDayTheVarianceBelongsToTheDayItWasRecorded() throws Exception {
        JsonNode report = reporting.report("margins", live.store(), day, day, "groupBy", "day");

        assertThat(report.get("rows")).hasSize(1);
        JsonNode row = report.get("rows").get(0);
        assertThat(row.get("key").asString()).isEqualTo(day.toString());
        assertThat(amount(row, "costOfSales")).isEqualTo("1.00");
        assertThat(amount(row, "waste")).isEqualTo("2.50");
        assertThat(amount(row, "costVariance")).isEqualTo("-5.00");
        assertThat(amount(row, "margin")).isEqualTo("25.55");
        assertThat(reporting.report("margins", live.store(), day.plusDays(1), day.plusDays(1), "groupBy", "day")
                .get("rows")).isEmpty();
    }

    @Test
    void aHeldCostProjectionIsPendingInTheMarginReport() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            held.countDown();
            release.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(projections).projectStockSource(eq("MANUAL_MOVEMENT"), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> wasted = pool.submit(() -> {
                kitchen.wasteFlour(live, "1");
                return null;
            });
            assertThat(held.await(20, TimeUnit.SECONDS)).as("the waste's projection started").isTrue();

            JsonNode whileHeld = reporting.report("margins", live.store(), day, day, "groupBy", "item");
            assertThat(whileHeld.get("pendingFacts").asLong()).isPositive();
            assertThat(whileHeld.get("final").asBoolean()).isFalse();

            release.countDown();
            wasted.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        kitchen.awaitRows("consumption_record", live.store(), 4);
        assertThat(amount(reporting.report("margins", live.store(), day, day, "groupBy", "item").get("totals"),
                "waste")).isEqualTo("4.50");
    }

    @Test
    void redeliveredCostFactsProjectNothingNew() throws Exception {
        String everything = """
                SELECT 'c' || movement_id || value AS r FROM reporting.consumption_record WHERE store_id = :s
                UNION ALL SELECT 'w' || void_id || value FROM reporting.consumption_waste_record WHERE store_id = :s
                UNION ALL SELECT 'v' || variance_id || value FROM reporting.cost_variance_record WHERE store_id = :s
                ORDER BY 1""";
        List<String> before = jdbc.sql(everything).param("s", live.store()).query(String.class).list();

        for (Map<String, Object> source : reporting.rows("""
                SELECT source_type, source_id FROM reporting.consumption_record WHERE store_id = ?
                UNION SELECT source_type, source_id FROM reporting.cost_variance_record WHERE store_id = ?""",
                live.store(), live.store())) {
            projections.projectStockSource((String) source.get("source_type"), (UUID) source.get("source_id"));
        }
        for (Map<String, Object> waste : reporting.rows(
                "SELECT void_id FROM reporting.consumption_waste_record WHERE store_id = ?", live.store())) {
            projections.projectWaste((UUID) waste.get("void_id"));
        }

        assertThat(before).hasSize(5);
        assertThat(jdbc.sql(everything).param("s", live.store()).query(String.class).list()).isEqualTo(before);
    }

    @Test
    void theStatusAsOfMovesWithACostProjection() throws Exception {
        java.time.Instant before = java.time.Instant.parse(reporting.reportStatus(live.store())
                .get("lastProjectedAt").asString());

        kitchen.wasteFlour(live, "1");
        kitchen.awaitRows("consumption_record", live.store(), 4);

        java.time.Instant newest = ((java.sql.Timestamp) reporting.row(
                "SELECT max(projected_at) AS at FROM reporting.consumption_record WHERE store_id = ?", live.store())
                .get("at")).toInstant();
        assertThat(newest).isAfter(before);
        assertThat(java.time.Instant.parse(reporting.reportStatus(live.store()).get("lastProjectedAt").asString()))
                .isEqualTo(newest);
    }

    @Test
    void theMarginsAreExportedAsCsvAndAudited() throws Exception {
        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(ReportingFixtures.REPORTS + "/exports")
                        .with(com.xavier.backend.cash.CashFixtures.account(live.store(), Permission.REPORT_VIEW,
                                Permission.REPORT_EXPORT))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""
                                {"report": "margins", "params": {"storeId": "%s", "from": "%s", "to": "%s",
                                 "groupBy": "day"}}""".formatted(live.store(), day, day)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\r\n")).containsExactly(
                "key,label,netSales,costOfSales,waste,costVariance,margin,marginPercent",
                day + "," + day + ",21.55,1.00,2.50,-5.00,25.55,118.56",
                "TOTAL,,21.55,1.00,2.50,-5.00,25.55,118.56");
        assertThat((String) reporting.row("""
                SELECT details::text AS details FROM audit.audit_event
                WHERE action = 'REPORT_EXPORTED' AND store_id = ?""", live.store()).get("details"))
                .contains("\"report\": \"margins\"").contains("\"rows\": 1");
    }

    private static String amount(JsonNode row, String field) {
        return row.get(field).get("amount").decimalValue().setScale(2).toPlainString();
    }
}
