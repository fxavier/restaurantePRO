package com.xavier.backend.promotions.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line to price. {@code key} is the caller's id for the line and comes back on its {@link PricedLine};
 * {@code listUnitPrice} is the catalog price the line would have without a promotion.
 */
public record PricingLine(UUID key, UUID itemId, UUID variantId, UUID familyId, Money listUnitPrice,
                          BigDecimal quantity) {
}
