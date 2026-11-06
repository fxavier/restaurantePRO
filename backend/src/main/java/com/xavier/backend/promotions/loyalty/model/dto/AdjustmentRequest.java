package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A manual adjustment of non-zero whole points; a blank reason is a 422 {@code REASON_REQUIRED} (§8.4). */
public record AdjustmentRequest(@NotNull Integer points, @Size(max = 500) String reason) {

    @AssertTrue(message = "must not be zero")
    public boolean isPointsNonZero() {
        return points == null || points != 0;
    }
}
