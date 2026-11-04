package com.xavier.backend.fiscal.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new SALE series for a store. {@code firstNumber} (default 1) is fixed at creation; nothing changes the next
 * number afterwards except issuing (spec §9).
 */
public record SeriesCreateRequest(@NotNull UUID storeId, @NotBlank @Size(max = 20) String code,
                                  @Min(1) Long firstNumber) {
}
