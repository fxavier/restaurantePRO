package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** The whole new state of an automatic item (its store does not change); {@code version} is the one read. */
public record AutomaticItemUpdateRequest(
        @NotNull @Pattern(regexp = "TABLE_OPEN_PER_GUEST|ORDER_OPEN") String trigger,
        @Size(max = 20) @Pattern(regexp = AutomaticItemCreateRequest.MODE_CODE) String mode,
        @NotNull UUID itemId,
        UUID variantId,
        @NotNull @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        boolean active,
        @NotNull Long version) {
}
