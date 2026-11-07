package com.xavier.backend.catalog.model.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The daily dishes of a store for a business date, in order (D109). */
public record DailyDishesView(UUID storeId, LocalDate businessDate, List<UUID> itemIds) {
}
