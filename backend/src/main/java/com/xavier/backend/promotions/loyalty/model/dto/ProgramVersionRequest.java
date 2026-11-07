package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A new DRAFT version: {@code pointsPerUnit} points per whole {@code spendUnit} of eligible gross, in the company
 * currency; the items and families that earn (both empty = every line).
 */
public record ProgramVersionRequest(@NotNull @Positive BigDecimal spendUnit, @NotNull @Positive Integer pointsPerUnit,
                                    @Size(max = 500) List<@NotNull UUID> itemIds,
                                    @Size(max = 500) List<@NotNull UUID> familyIds) {
}
