package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** @param version the version read, or {@code null} for a store that still uses the defaults */
public record DiningSettingsRequest(@NotNull @Min(1) @Max(1440) Integer reservationWindowMinutes,
                                    @NotNull @Min(0) @Max(1440) Integer noShowGraceMinutes, Long version) {
}
