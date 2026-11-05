package com.xavier.backend.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Reads of the reporting projections and reports for the module tests: waits (by polling a condition, never by a
 * fixed sleep) until a fact is projected, and sends the report requests as a BackOffice account of the store.
 */
@TestComponent
public class ReportingFixtures {

    public static final String REPORTS = "/api/v1/backoffice/reports";
    static final Duration PROJECTED = Duration.ofSeconds(20);

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final com.xavier.backend.settlement.SettlementFixtures sales;

    ReportingFixtures(MockMvc mvc, JdbcClient jdbc, com.xavier.backend.settlement.SettlementFixtures sales) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.sales = sales;
    }

    /** The order's ISSUED documents, oldest first. */
    public java.util.List<UUID> issuedDocuments(UUID order) {
        return jdbc.sql("""
                SELECT id FROM fiscal.fiscal_document WHERE order_ref = ? AND status = 'ISSUED'
                ORDER BY issued_at, id""").param(order).query(UUID.class).list();
    }

    public UUID issuedDocument(UUID order) {
        return issuedDocuments(order).getFirst();
    }

    public void awaitSalesRecord(UUID document) {
        await("sales record of " + document,
                () -> count("SELECT count(*) FROM reporting.sales_record WHERE document_id = ?", document) == 1);
    }

    public void awaitPaymentRecord(UUID payment) {
        await("payment record of " + payment, () -> count(
                "SELECT count(*) FROM reporting.payment_record WHERE source_type = 'PAYMENT' AND source_id = ?",
                payment) == 1);
    }

    public void awaitVoidRecord(UUID voidId) {
        await("void record of " + voidId,
                () -> count("SELECT count(*) FROM reporting.void_record WHERE void_id = ?", voidId) == 1);
    }

    /** Waits until every projection publication of the store's facts is completed (none pending for reporting). */
    public void awaitNothingPendingFor(UUID store) {
        await("reporting publications of " + store, () -> count("""
                SELECT count(*) FROM event_publication
                WHERE completion_date IS NULL AND listener_id LIKE 'com.xavier.backend.reporting.%'
                  AND serialized_event LIKE ?""", "%" + store + "%") == 0);
    }

    public static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + PROJECTED.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(20); // polls a condition; not a timing guard
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    public long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query(Long.class).single();
    }

    public Map<String, Object> row(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query().singleRow();
    }

    public java.util.List<Map<String, Object>> rows(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query().listOfRows();
    }

    /** Pays {@code amount} of the sale's order on an external terminal of {@code method}, confirmed (A1B2). */
    public void payByCard(com.xavier.backend.settlement.SettlementFixtures.Sale sale, String method, String amount)
            throws Exception {
        UUID attempt = com.xavier.backend.settlement.SettlementFixtures.id(sales().pay(sale.cashier(), sale.order(),
                method, amount, null, UUID.randomUUID().toString()).andExpect(status().isCreated()));
        mvc.perform(CashFixtures.idem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        com.xavier.backend.settlement.SettlementFixtures.ATTEMPTS + "/" + attempt + "/confirm"))
                .with(sale.cashier().with(Permission.PAYMENT_COLLECT))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\": \"A1B2\"}")).andExpect(status().isOk());
    }

    /** The order's business day (fixed when it opened, D85). */
    public LocalDate businessDayOf(UUID order) {
        return jdbc.sql("SELECT business_day FROM orders.order_header WHERE id = ?").param(order)
                .query(LocalDate.class).single();
    }

    /** The order's voids, oldest first. */
    public java.util.List<UUID> voidsOf(UUID order) {
        return jdbc.sql("SELECT id FROM orders.order_line_void WHERE order_id = ? ORDER BY id").param(order)
                .query(UUID.class).list();
    }

    /** The order's payments, oldest first. */
    public java.util.List<UUID> payments(UUID order) {
        return jdbc.sql("SELECT id FROM settlement.payment WHERE order_id = ? ORDER BY confirmed_at, id")
                .param(order).query(UUID.class).list();
    }

    /** Σ of the store's PAYMENT_RECEIPT and REFUND movements (signed) per method, in cash. */
    public Map<String, java.math.BigDecimal> movementsByMethod(UUID store) {
        Map<String, java.math.BigDecimal> sums = new java.util.TreeMap<>();
        jdbc.sql("""
                SELECT method_code, sum(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) AS signed
                FROM cash.cash_movement WHERE store_id = ? AND type IN ('PAYMENT_RECEIPT', 'REFUND')
                GROUP BY method_code""").param(store).query().listOfRows()
                .forEach(row -> sums.put((String) row.get("method_code"),
                        ((java.math.BigDecimal) row.get("signed")).setScale(2)));
        return sums;
    }

    private com.xavier.backend.settlement.SettlementFixtures sales() {
        return sales;
    }

    /** {@code GET /reports/{report}?storeId&from&to&…} as a BackOffice account holding REPORT_VIEW in the store. */
    public JsonNode report(String report, UUID store, LocalDate from, LocalDate to, String... extra)
            throws Exception {
        var request = get(REPORTS + "/" + report).param("storeId", store.toString())
                .param("from", from.toString()).param("to", to.toString())
                .with(CashFixtures.account(store, Permission.REPORT_VIEW));
        for (int i = 0; i < extra.length; i += 2) {
            request = request.param(extra[i], extra[i + 1]);
        }
        return CashFixtures.json(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
