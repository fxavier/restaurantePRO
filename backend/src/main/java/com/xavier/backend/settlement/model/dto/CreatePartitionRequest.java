package com.xavier.backend.settlement.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /api/v1/pos/orders/{id}/partitions} at the order version the POS read (stale → 409). {@code kind} is
 * {@code ITEMS} (with {@code lines}) or {@code AMOUNT} (with {@code amount}, in the order's currency).
 */
public record CreatePartitionRequest(@NotNull Long orderVersion, @NotNull @Pattern(regexp = "ITEMS|AMOUNT") String kind,
                                     @Valid @Size(max = 200) List<PartitionLineRequest> lines,
                                     @Positive BigDecimal amount, @Size(max = 100) String label) {
}
