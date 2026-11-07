package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Confirms a round (D50 §7.1). {@code orderVersion} is the order the POS shows (stale → 409); {@code catalogVersion}
 * the catalog it priced the drafts from (another published version → 409 {@code CATALOG_CHANGED}). R4 (D104):
 * {@code promotionVersion} the promotion release it previewed ({@code null} when none is published; another → 409
 * {@code PROMOTIONS_CHANGED}) and {@code expectedGross} the Σ preview gross it showed (optional; a different
 * recomputed gross → 409 {@code PRICES_CHANGED}).
 */
public record ConfirmBatchRequest(
        @NotNull Long orderVersion,
        @NotNull Long catalogVersion,
        @NotEmpty @Size(max = 200) List<@NotNull UUID> lineIds,
        Long promotionVersion,
        @PositiveOrZero @Digits(integer = 15, fraction = 2) BigDecimal expectedGross) {

    /** The R2/R3 request: no promotions previewed, no gross check. */
    public ConfirmBatchRequest(Long orderVersion, Long catalogVersion, List<UUID> lineIds) {
        this(orderVersion, catalogVersion, lineIds, null, null);
    }
}
