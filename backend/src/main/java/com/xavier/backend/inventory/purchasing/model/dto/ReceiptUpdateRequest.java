package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Replaces the document reference, arrival and lines of a DRAFT receipt (versioned, D17). */
public record ReceiptUpdateRequest(
        @Size(max = 60) String supplierDocumentRef,
        Instant receivedAt,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ReceiptLineRequest> lines,
        @NotNull Long version) {
}
