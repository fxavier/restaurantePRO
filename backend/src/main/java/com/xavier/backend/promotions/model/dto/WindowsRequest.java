package com.xavier.backend.promotions.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/** Replaces a promotion's windows (an empty list: at any time). Days are ISO numbers, 1 = Monday. */
public record WindowsRequest(@NotNull List<@Valid @NotNull Entry> windows, @NotNull Long version) {

    public record Entry(@NotEmpty List<@NotNull Integer> daysOfWeek, @NotNull LocalTime start, @NotNull LocalTime end) {
    }
}
