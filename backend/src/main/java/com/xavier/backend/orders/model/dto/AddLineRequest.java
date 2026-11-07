package com.xavier.backend.orders.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A new DRAFT line; {@code quantity} is decimal for items sold by weight (NUMERIC(12,3)). R4: {@code components} are
 * a MENU's choices, one per chosen option of a group (D108); empty or absent for any other item.
 */
public record AddLineRequest(
        @NotNull UUID itemId,
        UUID variantId,
        @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @Size(max = 200) String notes,
        @Size(max = 50) List<@Valid @NotNull Component> components) {

    public AddLineRequest {
        components = components == null ? List.of() : List.copyOf(components);
    }

    /** The R2/R3 request: no menu choices. */
    public AddLineRequest(UUID itemId, UUID variantId, BigDecimal quantity, String notes) {
        this(itemId, variantId, quantity, notes, List.of());
    }

    /** An option of the menu group {@code groupCode} ({@code variantId} null = the item itself). */
    public record Component(@NotBlank @Size(max = 30) String groupCode, @NotNull UUID itemId, UUID variantId) {
    }
}
