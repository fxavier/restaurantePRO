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

    ReportingFixtures(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
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
