package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A new DRAFT supplier return (D125). Linked to a confirmed receipt ({@code goodsReceiptId}), the warehouse and
 * supplier are the receipt's ({@code supplierId} may be omitted) and lines may name its receipt lines.
 */
public record SupplierReturnRequest(
        @NotNull UUID warehouseId,
        UUID supplierId,
        UUID goodsReceiptId,
        @Size(max = 500) String reason,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines) {
}
