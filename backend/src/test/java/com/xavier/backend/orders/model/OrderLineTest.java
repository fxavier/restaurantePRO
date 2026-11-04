package com.xavier.backend.orders.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** OrderLine DRAFT → CONFIRMED and OrderBatch CONFIRMED → DISPATCHED (r2-decisions §4 orders). */
class OrderLineTest {

    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static final UUID EMPLOYEE = UUID.randomUUID();
    static final LineSnapshot SNAPSHOT = new LineSnapshot("BURGER", "Burger", null,
            Money.of("12.50", Currency.getInstance("MZN")), UUID.randomUUID(), "IVA", new BigDecimal("16"),
            UUID.randomUUID(), 3, "KITCHEN");

    static OrderLine draft() {
        return OrderLine.draft(UUID.randomUUID(), UUID.randomUUID(), null, new BigDecimal("2"), "no onions",
                EMPLOYEE, NOW);
    }

    @Test
    void aDraftMayBeEditedAndHasNoSnapshot() {
        OrderLine line = draft();

        line.edit(null, new BigDecimal("0.457"), null);

        assertThat(line.getStatus()).isEqualTo(LineStatus.DRAFT);
        assertThat(line.getQuantity()).isEqualByComparingTo("0.457");
        assertThat(line.getNotes()).isNull();
        assertThat(line.snapshot()).isEmpty();
    }

    @Test
    void confirmingStampsTheSnapshotAndTheRound() {
        OrderLine line = draft();
        UUID batch = UUID.randomUUID();

        line.confirm(SNAPSHOT, batch, EMPLOYEE, NOW);

        assertThat(line.getStatus()).isEqualTo(LineStatus.CONFIRMED);
        assertThat(line.snapshot()).contains(SNAPSHOT);
        assertThat(line.getBatchId()).isEqualTo(batch);
        assertThat(line.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void aConfirmedLineCannotBeEditedDeletedOrConfirmedAgain() {
        OrderLine line = draft();
        line.confirm(SNAPSHOT, UUID.randomUUID(), EMPLOYEE, NOW);

        assertNotDraft(() -> line.edit(null, BigDecimal.ONE, null));
        assertNotDraft(line::requireDraft);
        assertNotDraft(() -> line.confirm(SNAPSHOT, UUID.randomUUID(), EMPLOYEE, NOW));
    }

    @Test
    void aQuantityMustBePositiveWithAtMostThreeDecimals() {
        assertThatThrownBy(() -> draft().edit(null, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> draft().edit(null, new BigDecimal("0.0005"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRoundIsDispatchedOnceAndADuplicateDispatchChangesNothing() {
        OrderBatch batch = OrderBatch.confirmed(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                EMPLOYEE, UUID.randomUUID(), 3, NOW);

        assertThat(batch.markDispatched(NOW)).isTrue();
        assertThat(batch.markDispatched(NOW.plusSeconds(5))).isFalse();
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.DISPATCHED);
        assertThat(batch.getDispatchedAt()).isEqualTo(NOW);
    }

    static void assertNotDraft(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OrderConflictException.class,
                e -> assertThat(e.code()).isEqualTo("LINE_NOT_DRAFT"));
    }
}
