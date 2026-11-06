package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The whole list of daily dishes of a store for a business date, in order; it replaces the previous list. */
public record DailyDishesRequest(@NotNull UUID storeId, @NotNull LocalDate businessDate,
                                 @NotNull @Size(max = 100) List<@NotNull UUID> itemIds) {
}
