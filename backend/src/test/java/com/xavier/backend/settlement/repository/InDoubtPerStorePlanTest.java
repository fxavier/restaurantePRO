package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * The POS alerts read the store's payments in doubt on every poll of every terminal, so both queries must stay on the
 * partial indexes over open attempts ({@code payment_attempt_store_unknown_ix}, or the open-per-target / next-poll ones the
 * planner may tie with on a small table), never on the whole attempt history.
 * The status is an enum literal in the JPQL, so the SQL carries {@code status = 'UNKNOWN'} as a constant the planner
 * matches to the index's predicate. Deterministic: 4 000 settled attempts and 20 in doubt, {@code ANALYZE}, then
 * {@code EXPLAIN} with {@code enable_seqscan = off} as the application role (row-level security included).
 */
@SettlementModuleTest
class InDoubtPerStorePlanTest {

    static final UUID TENANT = TenantTestSupport.BOOTSTRAP;

    /** The SQL of the two queries, translated by hand from their JPQL (pinned below). */
    static final String COUNT = """
            SELECT count(a.id) FROM settlement.payment_attempt a
            WHERE a.store_id = '%s' AND a.status = 'UNKNOWN'""";
    static final String OLDEST = """
            SELECT a.order_id FROM settlement.payment_attempt a
            WHERE a.store_id = '%s' AND a.status = 'UNKNOWN' AND a.order_id IS NOT NULL
            ORDER BY a.created_at LIMIT 1""";

    @Test
    void thePaymentsInDoubtOfAStoreAreReadThroughThePartialIndex() {
        UUID store = UUID.randomUUID();
        seed(store);
        OwnerDatabase.shared().jdbc().sql("ANALYZE settlement.payment_attempt").update();

        try (AppRoleSession app = AppRoleSession.open().tenant(TENANT).allStores()) {
            List<String> plans = app.inTransaction(jdbc -> {
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                return List.of(explain(jdbc, COUNT.formatted(store)), explain(jdbc, OLDEST.formatted(store)));
            });

            // Never the attempt history: no sequential scan, and every index the plan reads is a partial index over
            // open attempts (the new store index, or the open-per-target / next-poll ones the planner may tie with).
            assertThat(plans).allSatisfy(plan -> {
                assertThat(plan).doesNotContain("Seq Scan");
                assertThat(plan).containsPattern("(Index Only Scan using|Index Scan using|Bitmap Index Scan on) "
                        + "payment_attempt_(store_unknown_ix|open_per_target_uk|next_poll_ix)");
                assertThat(plan).doesNotContainPattern("(Index Only Scan using|Index Scan using|Bitmap Index Scan on) "
                        + "payment_attempt_(?!store_unknown_ix|open_per_target_uk|next_poll_ix)");
            });
        }
    }

    /** The JPQL keeps the literal status the plan above assumes: changing it breaks this test. */
    @Test
    void theRepositoryQueriesKeepThePartialIndexPredicate() throws Exception {
        String unknown = "a.status = com.xavier.backend.settlement.model.AttemptStatus.UNKNOWN";
        String count = jpql("countInDoubtInStore", UUID.class);
        String oldest = jpql("findInDoubtOrdersOfStore", UUID.class, Pageable.class);

        assertThat(count).contains("a.storeId = :storeId").contains(unknown);
        assertThat(oldest).contains("a.storeId = :storeId").contains(unknown).contains("a.orderId is not null")
                .contains("order by a.createdAt");
    }

    private static String jpql(String method, Class<?>... parameters) throws Exception {
        return PaymentAttemptRepository.class.getMethod(method, parameters).getAnnotation(Query.class).value()
                .replaceAll("\\s+", " ");
    }

    private static String explain(org.springframework.jdbc.core.simple.JdbcClient jdbc, String sql) {
        return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
    }

    private static void seed(UUID store) {
        try (AppRoleSession app = AppRoleSession.open().tenant(TENANT).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                        method_code, method_kind, provider_code, provider_reference, amount, currency, status,
                        created_by_employee_id, created_at, updated_at, version)
                    SELECT gen_random_uuid(), gen_random_uuid(), :store, gen_random_uuid(), gen_random_uuid(),
                        'FAKE_M', 'INTEGRATED', 'FAKE', gen_random_uuid()::text, 25.00, 'MZN',
                        CASE WHEN n <= 4000 THEN 'SUCCEEDED' ELSE 'UNKNOWN' END, gen_random_uuid(),
                        now() - make_interval(mins => n), now(), 0
                    FROM generate_series(1, 4020) AS n""").param("store", store).update();
        }
    }
}
