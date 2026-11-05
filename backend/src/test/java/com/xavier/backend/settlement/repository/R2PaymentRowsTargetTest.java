package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.model.TargetType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** V9005 (D69): an attempt and a payment written by R2 read as ORDER targets, keyed by their order. */
@SettlementModuleTest
@Import(PaymentRows.class)
class R2PaymentRowsTargetTest {

    @Autowired
    PaymentRows rows;

    @Autowired
    PaymentAttemptRepository attempts;

    @Test
    void anR2AttemptAndPaymentReadAsOrderTargetsKeyedByTheirOrder() {
        UUID order = UUID.randomUUID();
        UUID attempt = rows.r2Attempt(order, "10.00");
        UUID payment = UUID.randomUUID();
        rows.inTransaction(() -> {
            rows.r2Payment(payment, attempt, order, "10.00");
            rows.allocation(payment, "ORDER", order, "10.00");
        });

        assertThat(attempts.findById(attempt).orElseThrow().getTargetType()).isEqualTo(TargetType.ORDER);
        assertThat(attempts.findById(attempt).orElseThrow().toView().targetType()).isEqualTo("ORDER");
        assertThat(rows.value("SELECT target_key FROM settlement.payment_attempt WHERE id = ?", attempt, UUID.class))
                .isEqualTo(order);
        assertThat(rows.value("SELECT target_type FROM settlement.payment WHERE id = ?", payment, String.class))
                .isEqualTo("ORDER");
        assertThat(rows.value("SELECT target_key FROM settlement.payment WHERE id = ?", payment, UUID.class))
                .isEqualTo(order);
    }
}
