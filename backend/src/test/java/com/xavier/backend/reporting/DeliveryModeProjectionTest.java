package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.reporting.repository.ProjectionStore;
import com.xavier.backend.reporting.repository.ProjectionStore.SalesHeader;
import com.xavier.backend.reporting.repository.ProjectionStore.SalesLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D131: a sales line of a DELIVERY order (S50) is projected like any other mode, and the sales report groups it by
 * mode. The mode comes from the order's billing view, so the projection only has to accept it.
 */
@ReportingModuleTest
class DeliveryModeProjectionTest {

    static final LocalDate DAY = LocalDate.parse("2026-10-14");

    @Autowired
    ProjectionStore store;

    @Autowired
    CashFixtures cash;

    @Autowired
    ReportingFixtures reporting;

    @Test
    void aDeliveryLineIsProjectedAndReportedByMode() throws Exception {
        UUID storeId = cash.newStore();
        SalesHeader header = new SalesHeader(UUID.randomUUID(), storeId, DAY, DAY, 12, Instant.parse(
                "2026-10-14T10:00:00Z"), UUID.randomUUID(), null, null, null, UUID.randomUUID(), false, "D", 1,
                false, "MZN", new BigDecimal("11.60"), new BigDecimal("10.00"), new BigDecimal("1.60"));
        store.insertSalesHeader(header, Instant.now());

        store.insertSalesLine(header, new SalesLine(1, UUID.randomUUID(), "PIZZA", "FOOD", "Pizza",
                BigDecimal.ONE, new BigDecimal("11.60"), new BigDecimal("10.00"), new BigDecimal("1.60"), "IVA16",
                new BigDecimal("16"), null, null, null, "DELIVERY"), Instant.now());

        JsonNode byMode = reporting.report("sales", storeId, DAY, DAY, "groupBy", "mode");
        assertThat(byMode.get("rows")).hasSize(1);
        assertThat(byMode.get("rows").get(0).get("key").asString()).isEqualTo("DELIVERY");
        assertThat(byMode.get("rows").get(0).get("net").get("amount").decimalValue()).isEqualByComparingTo("10.00");
    }
}
