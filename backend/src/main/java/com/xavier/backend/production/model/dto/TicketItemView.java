package com.xavier.backend.production.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An item of a round ticket as printed: the quantity, the item (its variant in brackets, as printed), the line's note
 * ({@code null} when none) and the menu components that go to the ticket's station.
 */
public record TicketItemView(BigDecimal quantity, String name, String notes, List<TicketComponentView> components) {

    public TicketItemView {
        components = List.copyOf(components);
    }
}
