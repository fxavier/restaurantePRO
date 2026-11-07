package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Everything but the code, which deliveries snapshot; {@code active = false} retires the zone. */
public record ZoneUpdateRequest(@NotNull Long version, @NotBlank @Size(max = 80) String name,
                                @NotNull UUID feeItemId, UUID feeVariantId, boolean active) {
}
