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
}
