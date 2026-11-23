package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What arrived of the shipped lines, in the item's base unit (D120): a line left out arrived as shipped, so no lines
 * at all receive the transfer as sent. A shortfall is what did not arrive; {@code reason} explains it and is required
 * when any line is short (Q3 of the complete-app follow-ups), kept on each TRANSFER_SHORTAGE movement.
 */
public record ReceiveRequest(@Size(max = 200) List<@Valid @NotNull Line> lines, @Size(max = 500) String reason) {

    public ReceiveRequest {
        lines = lines == null ? List.of() : lines;
    }

    public record Line(
            @NotNull UUID stockItemId,
            @NotNull @Digits(integer = 13, fraction = 6) BigDecimal quantityReceived) {
    }
}
