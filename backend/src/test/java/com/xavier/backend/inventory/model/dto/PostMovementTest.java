package com.xavier.backend.inventory.model.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.model.MovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A posting is refused before anything is locked when its value has more than the ledger's 4 decimals. */
class PostMovementTest {

    @Test
    void aRevaluationValueHasAtMostFourDecimals() {
        PostMovement.revaluation(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.2340"), "COST_CORRECTION",
                UUID.randomUUID(), LocalDate.parse("2026-11-06"), Instant.parse("2026-11-06T10:00:00Z"));

        assertThatThrownBy(() -> PostMovement.revaluation(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.23456"), "COST_CORRECTION", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4 decimals");
    }

    @Test
    void aQuantityAndAUnitCostHaveAtMostSixDecimals() {
        assertThatThrownBy(() -> PostMovement.of(UUID.randomUUID(), UUID.randomUUID(), MovementType.RECEIPT,
                new BigDecimal("1.0000001"), "GOODS_RECEIPT", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T10:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
    }

    /** S47: an explicit value only on a CONSUMPTION_REVERSAL or a TRANSFER_IN at its own cost, never negative. */
    @Test
    void anExplicitValueIsForAReversalOrATransferInAndNeverNegative() {
        PostMovement in = PostMovement.of(UUID.randomUUID(), UUID.randomUUID(), MovementType.TRANSFER_IN,
                BigDecimal.ONE, "TRANSFER", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T10:00:00Z")).atCost(BigDecimal.ONE);
        in.withValue(new BigDecimal("0.9999"));

        assertThatThrownBy(() -> in.withValue(new BigDecimal("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostMovement.of(UUID.randomUUID(), UUID.randomUUID(), MovementType.RECEIPT,
                BigDecimal.ONE, "GOODS_RECEIPT", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T10:00:00Z")).atCost(BigDecimal.ONE).withValue(BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
