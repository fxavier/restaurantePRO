package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A new DRAFT purchase order (D124); {@code number} is unique in the warehouse's store. */
public record PurchaseOrderRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID supplierId,
        @NotBlank @Size(max = 30) String number,
        LocalDate expectedAt,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull PurchaseLineRequest> lines) {
}
