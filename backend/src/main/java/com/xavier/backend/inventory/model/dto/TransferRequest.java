package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** A new DRAFT transfer (D120). */
public record TransferRequest(
        @NotNull UUID fromWarehouseId,
        @NotNull UUID toWarehouseId,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull TransferLineRequest> lines,
        @Size(max = 500) String notes) {
}
