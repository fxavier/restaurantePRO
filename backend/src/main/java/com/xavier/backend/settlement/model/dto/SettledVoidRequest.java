package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/pos/orders/{id}/settlement/voids} (D71): {@code quantity} of {@code lineId} at the order version
 * the POS read, with its reason and whether the goods were wasted. The approver comes in the D40 headers.
 */
public record SettledVoidRequest(@NotNull Long orderVersion, @NotNull UUID lineId,
                                 @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity,
                                 @NotBlank @Size(max = 500) String reason, boolean wasted) {
}
