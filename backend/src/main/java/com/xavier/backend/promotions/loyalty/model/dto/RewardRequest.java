package com.xavier.backend.promotions.loyalty.model.dto;

import com.xavier.backend.promotions.loyalty.model.RewardEffect;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A reward of a DRAFT version (D112): {@code amount} only for {@code AMOUNT_OFF_UNIT}; scoped to an item, a family, or
 * (neither) any item. {@code code} is ignored when editing.
 */
public record RewardRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name,
                            @NotNull @Positive Integer pointsCost, @NotNull RewardEffect effect, BigDecimal amount,
                            UUID itemId, UUID familyId) {
}
