package com.xavier.backend.cash.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.settlement.AppRoleSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D171 (S67), A77: the open cash session of an operator
 * ({@link CashSessionRepository#findByStoreIdAndOperatorEmployeeIdAndStatus}, read by every payment to route its
 * money, P16) stays on the partial index {@code cash_session_operator_open_uk} under row-level security, as the
 * application role in a store scope (D169: {@code uuid_eq} and {@code texteq} are leakproof). Deterministic: one
 * register and day with 3 000 closed sessions and one open one, ANALYZE by the owner, then EXPLAIN in one transaction
 * with {@code enable_seqscan = off}.
 *
 * <p>D169 audit of cash: the one {@code numeric} predicate on a protected table, the discrepancy threshold of
 * {@code CashClosingRepository.discrepanciesSince}, compares an aggregate, not an indexed column.
 */
class OpenCashSessionPlanTest {

    /** The SQL of the derived query, by hand; its name pins the predicate. */
    static final String OPEN_SESSION = """
            SELECT s.* FROM cash.cash_session s
            WHERE s.store_id = ? AND s.operator_employee_id = ? AND s.status = 'OPEN'""";

    @Test
    void theOpenSessionOfAnOperatorIsReadThroughThePartialIndexUnderAStoreScope() {
        UUID store = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        seed(store, operator);

        try (AppRoleSession app = AppRoleSession.open()) {
            app.stores(store);
            List<String> plan = new ArrayList<>();
            app.inTransaction(() -> {
                app.jdbc().sql("SET LOCAL enable_seqscan = off").update();
                plan.addAll(app.jdbc().sql("EXPLAIN " + OPEN_SESSION).params(store, operator).query(String.class)
                        .list());
            });

            assertThat(String.join("\n", plan)).containsPattern(
                    "(Index Scan using|Bitmap Index Scan on) cash_session_operator_open_uk");
            assertThat(app.jdbc().sql(OPEN_SESSION).params(store, operator).query().listOfRows()).hasSize(1);
        }
    }

    @Test
    void theRepositoryMethodIsTheOneThePlanTranslates() throws Exception {
        assertThat(CashSessionRepository.class.getMethod("findByStoreIdAndOperatorEmployeeIdAndStatus", UUID.class,
                UUID.class, CashSessionStatus.class)).isNotNull();
    }

    private static void seed(UUID store, UUID operator) {
        UUID register = UUID.randomUUID();
        UUID day = UUID.randomUUID();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("""
                    INSERT INTO cash.cash_register (id, store_id, code, name, active, version, created_at, updated_at)
                    VALUES (?, ?, 'PLAN', 'Plano', true, 0, now(), now())""").params(register, store).update();
            owner.sql("""
                    INSERT INTO cash.cash_day (id, store_id, business_date, status, version, opened_at)
                    VALUES (?, ?, current_date, 'OPEN', 0, now())""").params(day, store).update();
            owner.sql("""
                    INSERT INTO cash.cash_session (id, store_id, register_id, cash_day_id, operator_employee_id,
                        terminal_id, status, opening_float, currency, opened_at, closed_at, closed_by_employee_id,
                        version, close_kind)
                    SELECT gen_random_uuid(), :store, :register, :day, gen_random_uuid(), :register, 'CLOSED', 0,
                        'MZN', now() - interval '1 day', now(), :register, 0, 'OPERATOR'
                    FROM generate_series(1, 3000)""").param("store", store).param("register", register)
                    .param("day", day).update();
            owner.sql("""
                    INSERT INTO cash.cash_session (id, store_id, register_id, cash_day_id, operator_employee_id,
                        terminal_id, status, opening_float, currency, opened_at, version)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'OPEN', 0, 'MZN', now(), 0)""")
                    .params(store, register, day, operator, register).update();
        });
        OwnerDatabase.shared().jdbc().sql("ANALYZE cash.cash_session").update();
    }
}
