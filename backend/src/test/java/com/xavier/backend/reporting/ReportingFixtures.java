package com.xavier.backend.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
    private final SettlementFixtures sales;
    private final OrdersFixtures orders;
    private final CashFixtures cash;
    private final FiscalFixtures fiscal;

    ReportingFixtures(MockMvc mvc, JdbcClient jdbc, SettlementFixtures sales, OrdersFixtures orders,
                      CashFixtures cash, FiscalFixtures fiscal) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.sales = sales;
        this.orders = orders;
        this.cash = cash;
        this.fiscal = fiscal;
    }

    /**
     * {@link SettlementFixtures#sale(String)} in a store of {@code zone} whose business day starts at {@code cutoff}:
     * the zone is set before the session and the order open, so both take their business day in it.
     */
    public Sale saleIn(ZoneId zone, LocalTime cutoff, String quantity) throws Exception {
        OrdersFixtures.Shop shop = orders.shop();
        JsonNode current = CashFixtures.json(mvc.perform(get("/api/v1/backoffice/stores/" + shop.store())
                        .with(CashFixtures.account(null, Permission.STORE_MANAGE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/v1/backoffice/stores/" + shop.store())
                        .with(CashFixtures.account(null, Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "%s", "timezone": "%s", "businessDayCutoff": "%s",
                                 "active": true, "version": %d}""".formatted(current.get("code").asString(),
                                current.get("name").asString(), zone.getId(), cutoff,
                                current.get("version").asLong())))
                .andExpect(status().isOk());
        UUID register = cash.newRegister(shop.store());
        Pos cashier = CashFixtures.pos(shop.store());
        UUID session = cash.openSession(cashier, register, "0");
        UUID series = fiscal.newSeries(shop.store(), "S" + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID order = OrdersFixtures.id(orders.openedOrder(shop, cashier.employeeId(), cashier.terminalId()));
        sales.confirmRound(shop, cashier, order, quantity, 0);
        return new Sale(shop, cashier, register, session, series, order);
    }

    /** The order's ISSUED documents, oldest first. */
    public List<UUID> issuedDocuments(UUID order) {
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

    public List<Map<String, Object>> rows(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query().listOfRows();
    }

    /** Pays {@code amount} of the sale's order on an external terminal of {@code method}, confirmed (A1B2). */
    public void payByCard(Sale sale, String method, String amount)
            throws Exception {
        UUID attempt = SettlementFixtures.id(sales.pay(sale.cashier(), sale.order(),
                method, amount, null, UUID.randomUUID().toString()).andExpect(status().isCreated()));
        mvc.perform(CashFixtures.idem(post(
                        SettlementFixtures.ATTEMPTS + "/" + attempt + "/confirm"))
                .with(sale.cashier().with(Permission.PAYMENT_COLLECT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\": \"A1B2\"}")).andExpect(status().isOk());
    }

    /** The order's business day (fixed when it opened, D85). */
    public LocalDate businessDayOf(UUID order) {
        return jdbc.sql("SELECT business_day FROM orders.order_header WHERE id = ?").param(order)
                .query(LocalDate.class).single();
    }

    /** The order's voids, oldest first. */
    public List<UUID> voidsOf(UUID order) {
        return jdbc.sql("SELECT id FROM orders.order_line_void WHERE order_id = ? ORDER BY id").param(order)
                .query(UUID.class).list();
    }

    /** The order's payments, oldest first. */
    public List<UUID> payments(UUID order) {
        return jdbc.sql("SELECT id FROM settlement.payment WHERE order_id = ? ORDER BY confirmed_at, id")
                .param(order).query(UUID.class).list();
    }

    /** Σ of the store's PAYMENT_RECEIPT and REFUND movements (signed) per method, in cash. */
    public Map<String, BigDecimal> movementsByMethod(UUID store) {
        Map<String, BigDecimal> sums = new TreeMap<>();
        jdbc.sql("""
                SELECT method_code, sum(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) AS signed
                FROM cash.cash_movement WHERE store_id = ? AND type IN ('PAYMENT_RECEIPT', 'REFUND')
                GROUP BY method_code""").param(store).query().listOfRows()
                .forEach(row -> sums.put((String) row.get("method_code"),
                        ((BigDecimal) row.get("signed")).setScale(2)));
        return sums;
    }

    /** Deletes every projection row of the store, as before its first projection. */
    public void wipeProjections(UUID store) {
        for (String table : List.of("sales_record", "payment_record", "credit_record", "void_record")) {
            jdbc.sql("DELETE FROM reporting." + table + " WHERE store_id = ?").param(store).update();
        }
    }

    /** {@code POST /reports/rebuild} as a BackOffice account holding REPORT_REBUILD in the store. */
    public ResultActions rebuild(UUID store, LocalDate from, LocalDate to, String key) throws Exception {
        return mvc.perform(CashFixtures.idem(post(REPORTS + "/rebuild"), key)
                .with(CashFixtures.account(store, Permission.REPORT_REBUILD))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "from": "%s", "to": "%s"}""".formatted(store, from, to)));
    }

    /** {@code GET /reports/status} as a BackOffice account holding REPORT_VIEW in the store. */
    public JsonNode reportStatus(UUID store) throws Exception {
        return CashFixtures.json(mvc.perform(get(REPORTS + "/status").param("storeId", store.toString())
                        .with(CashFixtures.account(store, Permission.REPORT_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Polls the store's status until its last rebuild is no longer RUNNING; returns that rebuild. */
    public JsonNode awaitRebuildFinished(UUID store) throws Exception {
        long deadline = System.nanoTime() + PROJECTED.toNanos();
        while (true) {
            JsonNode last = reportStatus(store).get("lastRebuild");
            if (last != null && !last.isNull() && !"RUNNING".equals(last.get("status").asString())) {
                return last;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the rebuild of " + store + " did not finish: " + last);
            }
            Thread.sleep(20); // polls a condition; not a timing guard
        }
    }

    /** A RUNNING rebuild of the store written directly, as one in progress would be. @return its id */
    public UUID runningRebuild(UUID store, LocalDate day) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reporting.rebuild_run (id, store_id, from_date, to_date, status, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', now())""").params(id, store, day, day).update();
        return id;
    }

    public void finishRebuild(UUID run) {
        jdbc.sql("UPDATE reporting.rebuild_run SET status = 'FAILED', finished_at = now() WHERE id = ?").param(run)
                .update();
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
