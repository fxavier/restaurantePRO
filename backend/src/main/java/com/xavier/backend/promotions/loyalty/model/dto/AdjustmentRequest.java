package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A manual adjustment of non-zero whole points, made in the name of {@code storeId} (authorized there, D42, and
 * recorded on the entry and the audit); a blank reason is a 422 {@code REASON_REQUIRED} (§8.4).
 */
public record AdjustmentRequest(@NotNull Integer points, @Size(max = 500) String reason, @NotNull UUID storeId) {

    @AssertTrue(message = "must not be zero")
    public boolean isPointsNonZero() {
        return points == null || points != 0;
    }
}
