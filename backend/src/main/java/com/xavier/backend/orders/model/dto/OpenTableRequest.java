package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Seats a party at a table (the table is the path; store and terminal come from the session, D39). R4 (D126):
 * {@code reservationId} seats that BOOKED reservation with the opening.
 */
public record OpenTableRequest(@NotNull @Positive @Max(999) Integer guests, UUID reservationId) {
}
