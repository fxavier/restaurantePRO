package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** The waste of one item over the period, as positive quantity and cost. */
public record WasteRow(UUID stockItemId, String code, String name, String baseUnit, BigDecimal quantity,
                       BigDecimal value) {
}
