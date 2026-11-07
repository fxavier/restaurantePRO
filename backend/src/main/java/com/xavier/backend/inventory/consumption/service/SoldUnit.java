package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What one consumption source sold (D116): a round line, or a component of a menu line (a menu line consumes only
 * its components). {@code quantity} is in sold units: the line's, times the component's for a component.
 */
record SoldUnit(String sourceType, UUID sourceId, UUID lineId, UUID itemId, UUID variantId, BigDecimal quantity,
                String destination) {

    static final String LINE = "ORDER_LINE";
    static final String COMPONENT = "ORDER_LINE_COMPONENT";

    static List<SoldUnit> of(BatchLineFact line) {
        if (line.components() == null || line.components().isEmpty()) {
            return List.of(new SoldUnit(LINE, line.lineId(), line.lineId(), line.itemId(), line.variantId(),
                    line.quantity(), line.destination()));
        }
        return line.components().stream().map(c -> new SoldUnit(COMPONENT, c.componentId(), line.lineId(),
                c.itemId(), c.variantId(), line.quantity().multiply(c.quantity()), c.destination())).toList();
    }

    static List<SoldUnit> of(BatchFacts round) {
        return round.lines().stream().flatMap(line -> of(line).stream()).toList();
    }
}
