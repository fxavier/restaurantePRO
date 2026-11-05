package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.OrderMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** A COUNTER or TAKEAWAY order (P15); {@code DINE_IN} is opened through its table (422). */
public record OpenOrderRequest(@NotNull OrderMode mode, @Positive @Max(999) Integer guests) {
}
