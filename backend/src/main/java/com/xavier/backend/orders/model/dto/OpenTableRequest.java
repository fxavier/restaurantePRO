package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Seats a party at a table (the table is the path; store and terminal come from the session, D39). */
public record OpenTableRequest(@NotNull @Positive @Max(999) Integer guests) {
}
