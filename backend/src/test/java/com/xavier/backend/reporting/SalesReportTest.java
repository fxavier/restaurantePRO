package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * After a seeded day (A28), sales by item, hour, employee and area match the issued documents, and taxes per rate
 * equal Σ {@code fiscal_document_tax}. The day: order 1 by the cashier (2 × 12.50 at 16% and 1 × 8.00 at 5%), order 2
 * at the same table by a second cashier (1 × 12.50).
 */
@ReportingModuleTest
class SalesReportTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    ReportingFixtures reporting;

    Sale sale;
    Pos second;
    LocalDate day;

    @BeforeEach
    void aDayOfTwoOrders() throws Exception {
        sale = sales.sale("2");
        sales.confirmOtherRate(sale, "8.00", "5", "1");
        sales.payCash(sale, "33.00", "33.00").andExpect(status().isCreated());
        sales.close(sale.cashier(), sale.order(), sales.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        second = sales.secondCashier(sale);
        UUID order = OrdersFixtures.id(orders.openedOrder(sale.shop(), second.employeeId(), second.terminalId()));
        long catalogVersion = orders.republish(sale.store()); // confirmOtherRate republished the catalog
        UUID line = OrdersFixtures.id(orders.draft(sale.shop(), order, "1", second.employeeId(), second.terminalId()));
        orders.confirm(sale.shop(), order, 0, catalogVersion, List.of(line), second.employeeId(),
                second.terminalId(), UUID.randomUUID().toString()).andExpect(status().isCreated());
        sales.pay(second, order, "CASH", "12.50", "12.50", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        sales.close(second, order, sales.orderVersion(second, order), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        reporting.issuedDocuments(sale.order()).forEach(reporting::awaitSalesRecord);
        reporting.issuedDocuments(order).forEach(reporting::awaitSalesRecord);
        day = reporting.businessDayOf(sale.order());
    }

    @Test
    void salesByItemMatchTheDocumentLines() throws Exception {
        JsonNode report = reporting.report("sales", sale.store(), day, day, "groupBy", "item");

        assertThat(rows(report, "key")).isEqualTo(documentLinesByItemCode()).hasSize(2);
        JsonNode totals = report.get("totals");
        assertThat(totals.get("documents").asLong()).isEqualTo(2);
        assertThat(totals.get("gross").get("amount").decimalValue()).isEqualByComparingTo("45.50");
    }

    @Test
    void salesByHourMatchTheDocumentsInTheStoresWallClock() throws Exception {
        JsonNode report = reporting.report("sales", sale.store(), day, day, "groupBy", "hour");

        Map<String, BigDecimal> expected = new TreeMap<>();
        reporting.rows("""
                SELECT lpad(extract(hour FROM issued_at AT TIME ZONE 'Africa/Maputo')::int::text, 2, '0') AS hour,
                       sum(total_gross) AS gross
                FROM fiscal.fiscal_document WHERE store_id = ? AND status = 'ISSUED' GROUP BY 1""", sale.store())
                .forEach(row -> expected.put((String) row.get("hour"), ((BigDecimal) row.get("gross")).setScale(2)));
        Map<String, BigDecimal> actual = new TreeMap<>();
        report.get("rows").forEach(row -> actual.put(row.get("key").asString(),
                row.get("gross").get("amount").decimalValue().setScale(2)));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void salesByEmployeeAreTheServersOfTheLines() throws Exception {
        JsonNode report = reporting.report("sales", sale.store(), day, day, "groupBy", "employee");

        assertThat(report.get("rows").valueStream().map(row -> tuple(row.get("key").asString(),
                        row.get("documents").asLong(), row.get("gross").get("amount").decimalValue().setScale(2))))
                .containsExactlyInAnyOrder(
                        tuple(sale.cashier().employeeId().toString(), 1L, new BigDecimal("33.00")),
                        tuple(second.employeeId().toString(), 1L, new BigDecimal("12.50")));
    }

    @Test
    void salesByAreaDayModeAndFamilyCoverTheWholeDay() throws Exception {
        for (String groupBy : List.of("area", "day", "mode", "terminal")) {
            JsonNode report = reporting.report("sales", sale.store(), day, day, "groupBy", groupBy);
            BigDecimal gross = report.get("rows").valueStream()
                    .map(row -> row.get("gross").get("amount").decimalValue()).reduce(BigDecimal.ZERO,
                            BigDecimal::add);
            assertThat(gross).as(groupBy).isEqualByComparingTo("45.50");
        }
        assertThat(reporting.report("sales", sale.store(), day, day, "groupBy", "area").get("rows").get(0)
                .get("label").asString()).isEqualTo("SALA");
        assertThat(reporting.report("sales", sale.store(), day, day, "groupBy", "day").get("rows").get(0)
                .get("key").asString()).isEqualTo(day.toString());
        assertThat(reporting.report("sales", sale.store(), day, day, "groupBy", "mode").get("rows").get(0)
                .get("key").asString()).isEqualTo("DINE_IN");
        assertThat(reporting.report("sales", sale.store(), day, day, "groupBy", "family").get("rows")
                .valueStream().map(row -> row.get("key").asString())).doesNotContainNull().isNotEmpty();
    }

    @Test
    void taxesPerRateEqualTheDocumentsTaxSummaries() throws Exception {
        JsonNode report = reporting.report("taxes", sale.store(), day, day);

        Map<BigDecimal, BigDecimal> expected = new TreeMap<>();
        reporting.rows("""
                SELECT t.tax_rate, sum(t.tax) AS tax FROM fiscal.fiscal_document_tax t
                JOIN fiscal.fiscal_document d ON d.id = t.document_id
                WHERE d.store_id = ? AND d.status = 'ISSUED' GROUP BY t.tax_rate""", sale.store())
                .forEach(row -> expected.put(((BigDecimal) row.get("tax_rate")).stripTrailingZeros(),
                        ((BigDecimal) row.get("tax")).setScale(2)));
        Map<BigDecimal, BigDecimal> actual = new TreeMap<>();
        report.get("rows").forEach(row -> actual.put(row.get("rate").decimalValue().stripTrailingZeros(),
                row.get("tax").get("amount").decimalValue().setScale(2)));
        assertThat(actual).isEqualTo(expected).hasSize(2);
        assertThat(report.get("rows").valueStream().map(row -> row.get("taxCode").asString()))
                .allMatch(code -> !code.isBlank());
    }

    @Test
    void topItemsAreRankedByQuantityOrGross() throws Exception {
        JsonNode byQuantity = reporting.report("top-items", sale.store(), day, day, "by", "quantity", "limit", "1");
        assertThat(byQuantity.get("rows")).hasSize(1);
        assertThat(byQuantity.get("rows").get(0).get("quantity").decimalValue()).isEqualByComparingTo("3");

        JsonNode byGross = reporting.report("top-items", sale.store(), day, day, "by", "gross", "limit", "100");
        assertThat(byGross.get("rows").valueStream().map(row -> row.get("gross").get("amount").decimalValue()
                .setScale(2))).containsExactly(new BigDecimal("37.50"), new BigDecimal("8.00"));
    }

    private static Map<String, List<BigDecimal>> rows(JsonNode report, String field) {
        Map<String, List<BigDecimal>> rows = new TreeMap<>();
        report.get("rows").forEach(row -> rows.put(row.get(field).asString(), List.of(
                row.get("quantity").decimalValue().setScale(3), row.get("gross").get("amount").decimalValue()
                        .setScale(2))));
        return rows;
    }

    /** The issued document lines summed per item code of the order line each one bills. */
    private Map<String, List<BigDecimal>> documentLinesByItemCode() {
        Map<String, List<BigDecimal>> lines = new TreeMap<>();
        reporting.rows("""
                SELECT o.item_code AS label, sum(l.quantity) AS quantity, sum(l.gross) AS gross
                FROM fiscal.fiscal_document_line l JOIN fiscal.fiscal_document d ON d.id = l.document_id
                JOIN orders.order_line o ON o.id = l.source_line_id
                WHERE d.store_id = ? AND d.status = 'ISSUED' GROUP BY 1""", sale.store())
                .forEach(row -> lines.put((String) row.get("label"), List.of(
                        ((BigDecimal) row.get("quantity")).setScale(3), ((BigDecimal) row.get("gross")).setScale(2))));
        return lines;
    }
}
