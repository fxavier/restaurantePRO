package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ZoneCreateRequest(@NotNull UUID storeId, @NotBlank @Size(max = 20) String code,
                                @NotBlank @Size(max = 80) String name, @NotNull UUID feeItemId, UUID feeVariantId) {
}
