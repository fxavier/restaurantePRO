package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A DRAFT's new rule and scope, at the version the client read (409 when stale). */
public record ProgramVersionEditRequest(@NotNull Long version, @NotNull @Positive BigDecimal spendUnit,
                                        @NotNull @Positive Integer pointsPerUnit,
                                        @Size(max = 500) List<@NotNull UUID> itemIds,
                                        @Size(max = 500) List<@NotNull UUID> familyIds) {
}
