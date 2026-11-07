package com.xavier.backend.orders.model.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A line as production prints it: the confirmed snapshot. {@code variantName} is {@code null} for the item itself and
 * {@code destination} ({@code KITCHEN}, {@code BAR}…) when nothing prepares it, so it is not printed (D56).
 *
 * @param components R4: a menu's chosen components (D108), empty for any other line; {@code null} in a publication
 *                   stored before R4. It stays the last component.
 */
@NamedInterface("event")
public record Line(UUID lineId, String itemCode, String description, String variantName, BigDecimal quantity,
                   String notes, String destination, List<ComponentLine> components) {

    /** The R2/R3 shape: no components. */
    public Line(UUID lineId, String itemCode, String description, String variantName, BigDecimal quantity,
                String notes, String destination) {
        this(lineId, itemCode, description, variantName, quantity, notes, destination, List.of());
    }
}
