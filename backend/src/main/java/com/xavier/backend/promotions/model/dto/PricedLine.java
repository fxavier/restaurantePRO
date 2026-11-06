package com.xavier.backend.promotions.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/**
 * The effective {@code unitPrice} of the request line {@code key}, next to its {@code listUnitPrice};
 * {@code promotion} is the promotion that set it, or null when none applies.
 */
public record PricedLine(UUID key, Money listUnitPrice, Money unitPrice, AppliedPromotion promotion) {
}
