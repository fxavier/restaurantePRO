package com.xavier.backend.inventory.model.dto;

import com.xavier.backend.inventory.model.NegativePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A new warehouse of {@code storeId}; {@code negativePolicy} defaults to ALLOW (D118). */
public record WarehouseRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        NegativePolicy negativePolicy) {
}
