package com.xavier.backend.orders.model;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a line keeps from the catalog when it is confirmed (spec §8.4, D58). {@code variantName} is {@code null} for
 * the item itself and {@code destination} when nothing prepares it (D56).
 */
public record LineSnapshot(String itemCode, String description, String variantName, Money unitPrice,
                           UUID taxCategoryId, String taxCode, BigDecimal taxRatePercent, UUID releaseId,
                           long catalogVersion, String destination) {
}
