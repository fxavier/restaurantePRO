package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new DRAFT goods receipt (D125, P51). Against a purchase order the supplier is the order's ({@code supplierId}
 * may be omitted); without one it is required. {@code supplierDocumentRef} is the supplier's delivery note or
 * invoice; {@code receivedAt} when the goods arrived (now by default).
 */
public record ReceiptRequest(
        @NotNull UUID warehouseId,
        UUID supplierId,
        UUID purchaseOrderId,
        @Size(max = 60) String supplierDocumentRef,
        Instant receivedAt,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ReceiptLineRequest> lines) {
}
