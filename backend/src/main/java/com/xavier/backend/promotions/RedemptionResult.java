package com.xavier.backend.promotions;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** The REDEEM entry, the points it spent, and the unit price of the rewarded unit (0 for a free unit). */
public record RedemptionResult(UUID entryId, int points, Money newUnitPrice) {
}
