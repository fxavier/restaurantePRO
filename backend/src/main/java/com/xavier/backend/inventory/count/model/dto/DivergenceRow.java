package com.xavier.backend.inventory.count.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One approved count line that moved stock (§8.5 "divergências"): expected at the cut-off, counted, the signed
 * difference and the value of its COUNT_ADJUSTMENT. {@code businessDate} is the cut-off's business day.
 */
public record DivergenceRow(UUID countId, UUID warehouseId, UUID stockItemId, String code, String name,
                            String baseUnit, Instant countedAt, LocalDate businessDate, BigDecimal expectedQuantity,
                            BigDecimal countedQuantity, BigDecimal difference, BigDecimal value) {
}
