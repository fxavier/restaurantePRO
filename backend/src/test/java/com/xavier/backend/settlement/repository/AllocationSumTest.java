package com.xavier.backend.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** D69, V9005: Σ allocations of a payment = its amount, checked by a deferred constraint trigger at commit. */
@SettlementModuleTest
@Import(PaymentRows.class)
class AllocationSumTest {

    @Autowired
    PaymentRows rows;

    @Test
    void aPaymentWithoutAnAllocationFailsAtCommit() {
        UUID order = UUID.randomUUID();
        UUID attempt = rows.r2Attempt(order, "10.00");

        assertThatThrownBy(() -> rows.inTransaction(() -> rows.r2Payment(UUID.randomUUID(), attempt, order, "10.00")))
                .hasStackTraceContaining("allocates");
    }

    @Test
    void anAllocationShortOfThePaymentIsAcceptedAtInsertButFailsAtCommit() {
        UUID order = UUID.randomUUID();
        UUID attempt = rows.r2Attempt(order, "10.00");
        UUID payment = UUID.randomUUID();

        assertThatThrownBy(() -> rows.inTransaction(() -> {
            rows.r2Payment(payment, attempt, order, "10.00");
            rows.allocation(payment, "ORDER", order, "9.99");
        })).hasStackTraceContaining("allocates");
        assertThat(rows.count("SELECT count(*) FROM settlement.payment WHERE id = ?", payment)).isZero();
    }

    @Test
    void allocationsToSeveralTargetsThatSumToThePaymentCommit() {
        UUID order = UUID.randomUUID();
        UUID attempt = rows.r2Attempt(order, "10.00");
        UUID payment = UUID.randomUUID();

        rows.inTransaction(() -> {
            rows.r2Payment(payment, attempt, order, "10.00");
            rows.allocation(payment, "ORDER", order, "4.00");
            rows.allocation(payment, "PARTITION", UUID.randomUUID(), "6.00");
        });

        assertThat(rows.count("SELECT count(*) FROM settlement.payment_allocation WHERE payment_id = ?", payment))
                .isEqualTo(2);
    }
}
