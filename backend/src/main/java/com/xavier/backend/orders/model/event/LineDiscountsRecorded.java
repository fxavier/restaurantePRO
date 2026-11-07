package com.xavier.backend.orders.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * Discounts were recorded on the order (D105): a round with at least one promoted line ({@code sourceType} BATCH,
 * {@code sourceId} the batch) or one price adjustment ({@code ADJUSTMENT}, its id). Reporting pulls
 * {@code OrdersApi.discountFacts(sourceType, sourceId)}; a redelivery names the same source.
 */
@NamedInterface("event")
public record LineDiscountsRecorded(UUID orderId, UUID storeId, String sourceType, UUID sourceId) {

    public static final String BATCH = "BATCH";
    public static final String ADJUSTMENT = "ADJUSTMENT";
}
