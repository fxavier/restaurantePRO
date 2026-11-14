package com.xavier.backend.reporting.repository;

import com.xavier.backend.OwnerDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.reporting.ReportingModuleTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The 31-day report queries are served by the {@code (store_id, business_date, …)} indexes (D86: < 1 s p95 for 31
 * days is measured in S33). Deterministic, so it does not depend on the planner statistics of a shared database: a
 * fixed seed of 12 stores × 400 business days, then {@code ANALYZE} and {@code EXPLAIN} of the repository's own SQL
 * (bound parameters) in one transaction with {@code enable_seqscan = off}. The assertions name the index node and its
 * condition on store and business-date range, so a query that filtered by anything else (a UTC range, D85) fails.
 */
@ReportingModuleTest
class ReportPlanTest {

    static final String INDEX_NODE = "(Index Scan using|Index Only Scan using|Bitmap Index Scan on) ";
    static final String STORE_AND_DAYS = "Index Cond: \\(\\(store_id = '[0-9a-f-]+'::uuid\\) AND "
            + "\\(business_date >= '2026-01-01'::date\\) AND \\(business_date <= '2026-01-31'::date\\)\\)";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    UUID store;

    @BeforeEach
    void twelveStoresOfFourHundredDays() {
        List<UUID> stores = java.util.stream.Stream.generate(UUID::randomUUID).limit(12).toList();
        store = stores.getFirst();
        for (UUID seeded : stores) {
            jdbc.sql("""
                    INSERT INTO reporting.sales_record (document_id, store_id, business_date, local_date, local_hour,
                        issued_at, terminal_id, on_account, series_code, number, non_fiscal, currency, total_gross,
                        total_net, total_tax, projected_at)
                    SELECT uuidv7(), :store, DATE '2025-06-01' + (i / 4), DATE '2025-06-01' + (i / 4), 12, now(),
                           gen_random_uuid(), false, 'A', i, true, 'MZN', 30, 25, 5, now()
                    FROM generate_series(0, 1599) i""").param("store", seeded).update();
            jdbc.sql("""
                    INSERT INTO reporting.sales_record_line (document_id, line_no, store_id, business_date, local_hour,
                        item_code, family_code, description, quantity, gross, net, tax, tax_code, tax_rate,
                        served_by_employee_id, area_code, mode, projected_at)
                    SELECT s.document_id, n, s.store_id, s.business_date, 12, 'I' || (s.number % 40), 'F', 'Item', 1,
                           10, 8.33, 1.67, 'IVA', 20, gen_random_uuid(), 'SALA', 'DINE_IN', now()
                    FROM reporting.sales_record s CROSS JOIN generate_series(1, 3) n
                    WHERE s.store_id = :store""").param("store", seeded).update();
            jdbc.sql("""
                    INSERT INTO reporting.sales_record_tax (document_id, tax_code, tax_rate, store_id, business_date,
                        net, tax, projected_at)
                    SELECT document_id, 'IVA', 20, store_id, business_date, 25, 5, now()
                    FROM reporting.sales_record WHERE store_id = :store""").param("store", seeded).update();
            jdbc.sql("""
                    INSERT INTO reporting.payment_record (source_type, source_id, store_id, business_date, local_hour,
                        method_code, signed_amount, target_type, employee_id, projected_at)
                    SELECT 'PAYMENT', uuidv7(), :store, DATE '2025-06-01' + (i / 4), 12,
                           CASE WHEN i % 2 = 0 THEN 'CASH' ELSE 'CARD' END, 30, 'ORDER', gen_random_uuid(), now()
                    FROM generate_series(0, 1599) i""").param("store", seeded).update();
            jdbc.sql("""
                    INSERT INTO reporting.credit_record (charge_id, store_id, business_date, customer_id, amount,
                        projected_at)
                    SELECT uuidv7(), :store, DATE '2025-06-01' + i, gen_random_uuid(), 10, now()
                    FROM generate_series(0, 399) i""").param("store", seeded).update();
            jdbc.sql("""
                    INSERT INTO reporting.void_record (void_id, store_id, business_date, local_hour, item_code,
                        description, quantity, gross, reason, wasted, after_dispatch, employee_id, projected_at)
                    SELECT uuidv7(), :store, DATE '2025-06-01' + i, 12, 'I1', 'Item', 1, 10, 'dropped', true, false,
                           gen_random_uuid(), now()
                    FROM generate_series(0, 399) i""").param("store", seeded).update();
        }
    }

    @Test
    void salesByEveryGroupingUseTheLineIndexOnStoreAndDays() {
        for (String[] grouping : new String[][] {{"l.business_date::text", "NULL::text"},
                {"l.item_code", "max(l.description)"}, {"l.served_by_employee_id::text", "NULL::text"},
                {"l.area_code", "NULL::text"}}) {
            String plan = explain(ReportQueries.SALES.formatted(grouping[0], grouping[1], "", ReportQueries.BY_KEY));
            assertThat(plan).as(grouping[0]).containsPattern(INDEX_NODE + "sales_record_line_store_day\\w*_ix")
                    .containsPattern(STORE_AND_DAYS);
        }
    }

    @Test
    void salesByTerminalJoinTheHeaderThroughItsKey() {
        String plan = explain(ReportQueries.SALES.formatted("s.terminal_id::text", "NULL::text",
                ReportQueries.HEADER_JOIN, ReportQueries.BY_KEY));

        assertThat(plan).containsPattern(INDEX_NODE + "sales_record_line_store_day\\w*_ix")
                .containsPattern(STORE_AND_DAYS)
                .containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) sales_record_(pk|"
                        + "store_day_ix)");
    }

    @Test
    void topItemsTaxesReceiptsCreditAndVoidsUseTheirStoreAndDayIndexes() {
        assertThat(explain(ReportQueries.SALES.formatted("l.item_code", "max(l.description)", "",
                "ORDER BY sum(l.quantity) DESC, 1 NULLS LAST LIMIT 20")))
                .containsPattern(INDEX_NODE + "sales_record_line_store_day\\w*_ix").containsPattern(STORE_AND_DAYS);
        assertThat(explain(ReportQueries.TAXES))
                .containsPattern(INDEX_NODE + "sales_record_tax_store_day_ix").containsPattern(STORE_AND_DAYS);
        assertThat(explain(ReportQueries.RECEIPTS.formatted("method_code")))
                .containsPattern(INDEX_NODE + "payment_record_store_day\\w*_ix").containsPattern(STORE_AND_DAYS);
        assertThat(explain(ReportQueries.CREDIT.formatted("business_date::text")))
                .containsPattern(INDEX_NODE + "credit_record_store_day_ix").containsPattern(STORE_AND_DAYS);
        assertThat(explain(ReportQueries.VOIDS.formatted("reason", "NULL::text")))
                .containsPattern(INDEX_NODE + "void_record_store_day_ix").containsPattern(STORE_AND_DAYS);
    }

    private String explain(String sql) {
        return tx.execute(status -> {
            for (String table : List.of("sales_record", "sales_record_line", "sales_record_tax", "payment_record",
                    "credit_record", "void_record")) {
                // ANALYZE needs the table owner: as the pool it is skipped with a warning (ADR 0039)
                OwnerDatabase.shared().jdbc().sql("ANALYZE reporting." + table).update();
            }
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + sql).param("store", store)
                    .param("from", LocalDate.of(2026, 1, 1)).param("to", LocalDate.of(2026, 1, 31))
                    .param("limit", 20).query(String.class).list();
            return String.join("\n", lines);
        });
    }
}
