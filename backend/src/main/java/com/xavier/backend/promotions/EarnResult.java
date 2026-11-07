package com.xavier.backend.promotions;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** The EARN entry of an order: its points, the program version whose rule earned them, and the eligible gross. */
public record EarnResult(UUID entryId, int points, UUID programVersionId, Money eligibleGross) {
}
