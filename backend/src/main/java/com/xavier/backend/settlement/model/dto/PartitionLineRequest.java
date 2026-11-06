package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/** One order line's quantity in an ITEMS partition (at most 3 decimals, like the line). */
public record PartitionLineRequest(@NotNull UUID lineId, @NotNull @Positive @Digits(integer = 9, fraction = 3)
                                   BigDecimal quantity) {
}
