package com.xavier.backend.orders.model.event;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A line as production prints it: the confirmed snapshot. {@code variantName} is {@code null} for the item itself and
 * {@code destination} ({@code KITCHEN}, {@code BAR}…) when nothing prepares it, so it is not printed (D56).
 */
@NamedInterface("event")
public record Line(UUID lineId, String itemCode, String description, String variantName, BigDecimal quantity,
                   String notes, String destination) {
}
