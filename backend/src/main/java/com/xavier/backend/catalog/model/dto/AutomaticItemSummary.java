package com.xavier.backend.catalog.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An automatic item (D109); {@code storeId} null = every store.
 *
 * @param trigger {@code TABLE_OPEN_PER_GUEST} or {@code ORDER_OPEN}
 * @param mode    for ORDER_OPEN the order mode, {@code null} = every mode
 */
public record AutomaticItemSummary(UUID id, UUID storeId, String trigger, String mode, UUID itemId, UUID variantId,
                                   BigDecimal quantity, boolean active, long version) {
}
