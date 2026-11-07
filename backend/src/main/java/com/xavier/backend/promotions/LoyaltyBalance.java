package com.xavier.backend.promotions;

import java.util.UUID;

/**
 * A customer's loyalty standing: whether they are enrolled, the account status ({@code ACTIVE}, {@code SUSPENDED}, or
 * null when not enrolled) and the points balance, always Σ of the ledger (D110; 0 when not enrolled).
 */
public record LoyaltyBalance(UUID customerId, boolean enrolled, String status, long points) {
}
