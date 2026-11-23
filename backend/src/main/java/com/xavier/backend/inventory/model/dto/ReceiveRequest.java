package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What arrived of every shipped line, in the item's base unit (D120); a shortfall is what did not. {@code reason}
 * explains a shortfall and is required when any line is short (Q3 of the complete-app follow-ups); it is kept on each
 * TRANSFER_SHORTAGE movement and the receipt's audit event, and ignored on a full receipt.
 */
public record ReceiveRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines,
                             @Size(max = 500) String reason) {

    public record Line(
            @NotNull UUID stockItemId,
            @NotNull @Digits(integer = 13, fraction = 6) BigDecimal quantityReceived) {
    }
}
