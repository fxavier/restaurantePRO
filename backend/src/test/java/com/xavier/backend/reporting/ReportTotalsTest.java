package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.cash.CashFixtures;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * S53 review: a report's totals are the sum of its rows as shown, so an export's rows always add up to its TOTAL.
 * Three groups of 0.3333 each show 0.33 and total 0.99, not 1.00. Seeded directly in the projections of a fresh store.
 */
@ReportingModuleTest
class ReportTotalsTest {

    static final LocalDate DAY = LocalDate.parse("2026-10-20");

    @Autowired
    CashFixtures cash;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    JdbcClient jdbc;

    UUID store;

    @BeforeEach
    void aStore() throws Exception {
        store = cash.newStore();
    }

    @Test
    void threeOffersOfAThirdEachTotalWhatTheyShow() throws Exception {
        UUID round = UUID.randomUUID();
        consumption(round, UUID.randomUUID(), "-1.0000"); // the round line of 3 consumed 1.00
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    INSERT INTO reporting.offer_cost_record (order_id, line_id, store_id, business_date,
                        operator_employee_id, net_quantity, consumed_line_id, consumed_line_quantity, projected_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, 3, now())""")
                    .params(UUID.randomUUID(), UUID.randomUUID(), store, DAY, UUID.randomUUID(), round).update();
        }

        JsonNode report = reporting.report("discounts", store, DAY, DAY, "groupBy", "employee");

        assertThat(report.get("rows")).hasSize(3).allSatisfy(row ->
                assertThat(row.get("offerCost").get("amount").decimalValue()).isEqualByComparingTo("0.33"));
        assertThat(report.get("totals").get("offerCost").get("amount").decimalValue()).isEqualByComparingTo("0.99");
    }

    @Test
    void threeItemsCostingAThirdEachTotalWhatTheyShow() throws Exception {
        for (int i = 0; i < 3; i++) {
            consumption(UUID.randomUUID(), UUID.randomUUID(), "-0.3333");
        }

        JsonNode report = reporting.report("margins", store, DAY, DAY, "groupBy", "item");

        assertThat(report.get("rows")).hasSize(3);
        assertThat(report.get("totals").get("costOfSales").get("amount").decimalValue()).isEqualByComparingTo("0.99");
        assertThat(report.get("totals").get("margin").get("amount").decimalValue()).isEqualByComparingTo("-0.99");
    }

    private void consumption(UUID line, UUID item, String value) {
        UUID movement = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reporting.consumption_record (movement_id, store_id, business_date, sale_item_id, line_id,
                    warehouse_id, stock_item_id, type, quantity, value, source_type, source_id, projected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SALE_CONSUMPTION', -1, ?::numeric, 'ORDER_LINE', ?, now())""")
                .params(movement, store, DAY, item, line, UUID.randomUUID(), UUID.randomUUID(), value, line).update();
    }
}
