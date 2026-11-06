package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What arrived of every shipped line, in the item's base unit (D120); a shortfall is what did not. */
public record ReceiveRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines) {

    public record Line(
            @NotNull UUID stockItemId,
            @NotNull @Digits(integer = 13, fraction = 6) BigDecimal quantityReceived) {
    }
}
