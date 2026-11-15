package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.settlement.AppRoleSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D171 (S67), A77: the payments of an order ({@link PaymentRepository#findByOrderIdOrderByConfirmedAtAscIdAsc}, read
 * on every bill, close and refund) stay on {@code payment_order_ix} under row-level security, as the application role
 * in a store scope: the policy is an InitPlan filter on top, and {@code uuid_eq} is leakproof, so the query's own
 * predicate stays the index condition (D169). Deterministic: 3 000 payments of 1 000 orders in one store, ANALYZE by
 * the owner, then EXPLAIN in one transaction with {@code enable_seqscan = off}.
 *
 * <p>D169 audit of settlement: no query on a protected table relies on an index for a {@code numeric}, {@code jsonb}
 * or {@code LIKE} predicate (amounts are only summed or compared after aggregation).
 */
class PaymentsOfOrderPlanTest {

    /** The SQL of the derived query, by hand; its name pins the predicate and the order. */
    static final String PAYMENTS_OF_ORDER = """
            SELECT p.* FROM settlement.payment p WHERE p.order_id = ? ORDER BY p.confirmed_at, p.id""";

    @Test
    void thePaymentsOfAnOrderAreReadThroughTheOrderIndexUnderAStoreScope() throws Exception {
        UUID store = UUID.randomUUID();
        String salt = store.toString();
        seed(store, salt);
        UUID order = OwnerDatabase.shared().jdbc().sql("SELECT md5(? || 'o' || 7)::uuid").param(salt)
                .query(UUID.class).single();

        try (AppRoleSession app = AppRoleSession.open()) {
            app.stores(store);
            List<String> plan = new java.util.ArrayList<>();
            app.inTransaction(() -> {
                app.jdbc().sql("SET LOCAL enable_seqscan = off").update();
                plan.addAll(app.jdbc().sql("EXPLAIN " + PAYMENTS_OF_ORDER).param(order).query(String.class).list());
            });

            assertThat(String.join("\n", plan)).containsPattern(
                    "(Index Scan using|Bitmap Index Scan on) payment_order_ix");
            assertThat(app.jdbc().sql(PAYMENTS_OF_ORDER).param(order).query().listOfRows()).hasSize(3);
        }
    }

    @Test
    void theRepositoryMethodIsTheOneThePlanTranslates() throws Exception {
        assertThat(PaymentRepository.class.getMethod("findByOrderIdOrderByConfirmedAtAscIdAsc", UUID.class))
                .isNotNull();
    }

    private static void seed(UUID store, String salt) {
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("""
                    INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                        method_code, method_kind, amount, tendered, change_given, currency, status,
                        created_by_employee_id, created_at, updated_at, version)
                    SELECT md5(:salt || 'a' || n)::uuid, md5(:salt || 'o' || (n % 1000))::uuid, :store, :store,
                        :store, 'CASH', 'CASH', 10, 10, 0, 'MZN', 'SUCCEEDED', :store, now(), now(), 0
                    FROM generate_series(1, 3000) AS n""").param("salt", salt).param("store", store).update();
            owner.sql("""
                    INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code,
                        amount, currency, status, collected_by_employee_id, confirmed_at)
                    SELECT md5(:salt || 'p' || n)::uuid, md5(:salt || 'a' || n)::uuid,
                        md5(:salt || 'o' || (n % 1000))::uuid, :store, :store, 'CASH', 10, 'MZN', 'CONFIRMED',
                        :store, now() - n * interval '1 second'
                    FROM generate_series(1, 3000) AS n""").param("salt", salt).param("store", store).update();
            owner.sql("""
                    INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount,
                        currency, created_at)
                    SELECT md5(:salt || 'l' || n)::uuid, md5(:salt || 'p' || n)::uuid, 'ORDER',
                        md5(:salt || 'o' || (n % 1000))::uuid, 10, 'MZN', now()
                    FROM generate_series(1, 3000) AS n""").param("salt", salt).update();
        });
        OwnerDatabase.shared().jdbc().sql("ANALYZE settlement.payment").update();
    }
}
