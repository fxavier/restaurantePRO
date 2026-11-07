package com.xavier.backend.promotions;

import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Redeem the reward {@code rewardCode} on the confirmed one-unit line {@code sourceLineId} (D112): the order, its store
 * and business day, the line's item and family (for the reward's scope) and the unit price before the reward.
 */
public record RedeemCommand(UUID customerId, String rewardCode, UUID sourceLineId, UUID orderId, UUID storeId,
                            LocalDate businessDate, UUID itemId, UUID familyId, Money currentUnitPrice) {

    public RedeemCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(rewardCode, "rewardCode");
        Objects.requireNonNull(sourceLineId, "sourceLineId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(currentUnitPrice, "currentUnitPrice");
    }
}
