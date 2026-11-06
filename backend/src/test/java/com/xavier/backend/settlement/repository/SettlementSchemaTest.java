package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the database itself guarantees (A15 settlement part, spec §8): payments and allocations are facts that cannot
 * be updated or deleted, an attempt is paid at most once, and an order has at most one open attempt.
 */
@SettlementModuleTest
class SettlementSchemaTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aPaymentAndItsAllocationCannotBeUpdatedOrDeleted() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        UUID payment = jdbc.sql("SELECT id FROM settlement.payment WHERE order_id = ?").param(sale.order())
                .query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("UPDATE settlement.payment SET amount = 1 WHERE id = ?").param(payment)
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM settlement.payment_allocation WHERE payment_id = ?")
                .param(payment).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
    }

    @Test
    void anAttemptIsPaidOnceAndAnOrderTargetHasOneOpenAttempt() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID attempt = SettlementFixtures.id(fixtures.payCash(sale, "10.00", "10.00"));

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code, amount,
                    currency, status, collected_by_employee_id, confirmed_at)
                SELECT uuidv7(), attempt_id, order_id, store_id, session_id, method_code, amount, currency, status,
                    collected_by_employee_id, confirmed_at FROM settlement.payment WHERE attempt_id = ?""")
                .param(attempt).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payment_attempt_uk");
        assertThatThrownBy(() -> {
            openAttempt(sale);
            openAttempt(sale);
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payment_attempt_open_per_target_uk");
    }

    private void openAttempt(Sale sale) {
        jdbc.sql("""
                INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id, method_code,
                    method_kind, amount, currency, status, created_by_employee_id, created_at, updated_at, version)
                VALUES (uuidv7(), ?, ?, ?, ?, 'CARD', 'EXTERNAL_TERMINAL', 1, 'MZN', 'PENDING', ?, now(), now(), 0)""")
                .params(sale.order(), sale.store(), sale.cashier().terminalId(), sale.session(),
                        sale.cashier().employeeId()).update();
    }
}
