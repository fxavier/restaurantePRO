package com.xavier.backend.inventory.count.model.dto;

import com.xavier.backend.inventory.count.model.CountKind;
import com.xavier.backend.inventory.count.model.CountScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** A new count of a warehouse: {@code stockItemIds} for a LIST count only. */
public record CountRequest(@NotNull UUID warehouseId, @NotNull CountKind kind, @NotNull CountScope scope,
                           @Size(max = 2000) List<@NotNull UUID> stockItemIds) {

    public List<UUID> listed() {
        return stockItemIds == null ? List.of() : stockItemIds;
    }
}
