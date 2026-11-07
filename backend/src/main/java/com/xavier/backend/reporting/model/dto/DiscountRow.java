package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Discounts and offers of one group (D131, §8.6): how many discounted lines, their quantity, the gross given away
 * ({@code discount}; a reversal counts negative) and {@code offerCost}, the consumption cost of the offered lines.
 */
public record DiscountRow(String key, String label, long lines, BigDecimal quantity, Money discount,
                          Money offerCost) {
}
