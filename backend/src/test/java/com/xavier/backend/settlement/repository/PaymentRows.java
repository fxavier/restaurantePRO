package com.xavier.backend.settlement.repository;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment rows written straight through SQL the way an R2 build wrote them (no target columns). */
@TestComponent
class PaymentRows {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    PaymentRows(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    UUID r2Attempt(UUID order, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id, method_code,
                    method_kind, amount, tendered, change_given, currency, status, created_by_employee_id, created_at,
                    updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'CASH', 'CASH', ?, ?, 0, 'MZN', 'SUCCEEDED', ?, now(), now(), 0)""")
                .params(id, order, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount),
                        new BigDecimal(amount), UUID.randomUUID()).update();
        return id;
    }

    void r2Payment(UUID id, UUID attempt, UUID order, String amount) {
        jdbc.sql("""
                INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code, amount,
                    currency, status, collected_by_employee_id, confirmed_at)
                VALUES (?, ?, ?, ?, ?, 'CASH', ?, 'MZN', 'CONFIRMED', ?, now())""")
                .params(id, attempt, order, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount),
                        UUID.randomUUID()).update();
    }

    void allocation(UUID payment, String targetType, UUID target, String amount) {
        jdbc.sql("""
                INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount, currency,
                    created_at) VALUES (?, ?, ?, ?, ?, 'MZN', now())""")
                .params(UUID.randomUUID(), payment, targetType, target, new BigDecimal(amount)).update();
    }

    /** A refund row of {@code payment} (an ORDER target), as settlement writes it. */
    void refund(UUID payment, UUID order, String amount, UUID operator, UUID approver) {
        jdbc.sql("""
                INSERT INTO settlement.refund (id, payment_id, store_id, target_type, order_id, method_code, amount,
                    currency, reason, session_id, terminal_id, business_date, operator_employee_id,
                    approver_employee_id, confirmed_at)
                VALUES (?, ?, ?, 'ORDER', ?, 'CASH', ?, 'MZN', 'returned', ?, ?, current_date, ?, ?, now())""")
                .params(UUID.randomUUID(), payment, UUID.randomUUID(), order, new BigDecimal(amount),
                        UUID.randomUUID(), UUID.randomUUID(), operator, approver).update();
    }

    void inTransaction(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    long count(String sql, Object id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }

    <T> T value(String sql, Object id, Class<T> type) {
        return jdbc.sql(sql).param(id).query(type).single();
    }
}
