package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * V9030: a Refund is a fact (no UPDATE, no DELETE), its approver is not its operator, and Σ refunds of a payment never
 * exceeds the payment, checked at commit by a deferred constraint trigger, so two refunds summed in one transaction
 * are caught too.
 */
@SettlementModuleTest
@Import(PaymentRows.class)
class RefundSchemaTest {

    @Autowired
    PaymentRows rows;

    UUID order;
    UUID payment;

    @BeforeEach
    void aPaymentOfTen() {
        order = UUID.randomUUID();
        payment = UUID.randomUUID();
        UUID attempt = rows.r2Attempt(order, "10.00");
        rows.inTransaction(() -> {
            rows.r2Payment(payment, attempt, order, "10.00");
            rows.allocation(payment, "ORDER", order, "10.00");
        });
    }

    @Test
    void refundsUpToThePaymentCommit() {
        rows.inTransaction(() -> {
            rows.refund(payment, order, "4.00", UUID.randomUUID(), UUID.randomUUID());
            rows.refund(payment, order, "6.00", UUID.randomUUID(), UUID.randomUUID());
        });

        assertThat(rows.count("SELECT count(*) FROM settlement.refund WHERE payment_id = ?", payment)).isEqualTo(2);
    }

    @Test
    void refundsBeyondThePaymentFailAtCommit() {
        rows.inTransaction(() -> rows.refund(payment, order, "6.00", UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> rows.inTransaction(() -> rows.refund(payment, order, "4.01", UUID.randomUUID(),
                UUID.randomUUID()))).hasStackTraceContaining("refunds");
        assertThat(rows.count("SELECT count(*) FROM settlement.refund WHERE payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void theApproverIsNotTheOperator() {
        UUID same = UUID.randomUUID();

        assertThatThrownBy(() -> rows.inTransaction(() -> rows.refund(payment, order, "1.00", same, same)))
                .hasStackTraceContaining("refund_approver_ck");
    }

    @Test
    void aRefundIsNeverChangedNorDeleted() {
        rows.inTransaction(() -> rows.refund(payment, order, "1.00", UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> rows.inTransaction(() -> rows.count(
                "WITH c AS (UPDATE settlement.refund SET amount = 2 WHERE payment_id = ? RETURNING 1) "
                        + "SELECT count(*) FROM c", payment))).isNotNull();
        assertThatThrownBy(() -> rows.inTransaction(() -> rows.count(
                "WITH c AS (DELETE FROM settlement.refund WHERE payment_id = ? RETURNING 1) SELECT count(*) FROM c",
                payment))).isNotNull();
        assertThat(rows.value("SELECT amount FROM settlement.refund WHERE payment_id = ?", payment,
                java.math.BigDecimal.class)).isEqualByComparingTo("1.00");
    }
}
