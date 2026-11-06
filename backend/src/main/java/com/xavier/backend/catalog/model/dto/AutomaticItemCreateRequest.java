package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An automatic item for {@code storeId}, or every store when null. {@code mode} only with {@code ORDER_OPEN}; it is an
 * order mode code (DINE_IN, TAKEAWAY…). {@code active} null means true.
 */
public record AutomaticItemCreateRequest(
        UUID storeId,
        @NotNull @Pattern(regexp = "TABLE_OPEN_PER_GUEST|ORDER_OPEN") String trigger,
        @Size(max = 20) @Pattern(regexp = AutomaticItemCreateRequest.MODE_CODE) String mode,
        @NotNull UUID itemId,
        UUID variantId,
        @NotNull @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        Boolean active) {

    static final String MODE_CODE = "^[A-Z][A-Z_]*$";
}
