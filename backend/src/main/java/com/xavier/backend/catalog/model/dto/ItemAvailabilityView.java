package com.xavier.backend.catalog.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Whether an item ({@code variantId} null: with all its variants) or a variant is sold out in the session's store
 * (D109). {@code soldOutAt} and {@code reason} are set only while it is sold out.
 */
public record ItemAvailabilityView(UUID itemId, UUID variantId, boolean soldOut, Instant soldOutAt, String reason) {
}
