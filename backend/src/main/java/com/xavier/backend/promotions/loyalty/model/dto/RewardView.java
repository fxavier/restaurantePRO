package com.xavier.backend.promotions.loyalty.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** A reward as the BackOffice sees it; {@code amount} is null for a free unit. */
public record RewardView(UUID id, String code, String name, int pointsCost, String effect, Money amount, UUID itemId,
                         UUID familyId) {
}
