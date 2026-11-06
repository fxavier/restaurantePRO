package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An inventory report of a store (D131): its rows and the total value; {@code from}/{@code to} when it has a period. */
public record InventoryReport<T>(UUID storeId, LocalDate from, LocalDate to, List<T> rows, BigDecimal totalValue) {
}
