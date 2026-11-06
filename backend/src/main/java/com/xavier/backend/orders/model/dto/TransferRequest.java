package com.xavier.backend.orders.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Moves confirmed quantities from the order in the path to {@code targetOrderId} (same store). Both versions are the
 * ones the POS shows (stale → 409). A merge moves everything with {@code cancelSourceWhenEmpty = true}: the source is
 * then cancelled when it bills nothing any more, freeing its table.
 */
public record TransferRequest(
        @NotNull Long orderVersion,
        @NotNull UUID targetOrderId,
        @NotNull Long targetOrderVersion,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull TransferLineRequest> lines,
        boolean cancelSourceWhenEmpty) {
}
