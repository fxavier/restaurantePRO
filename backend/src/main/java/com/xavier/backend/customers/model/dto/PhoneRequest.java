package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A phone to add; {@code primary} demotes the current primary one (a customer's first phone is always primary). */
public record PhoneRequest(@NotNull @PhoneDigits String number, @Size(max = 40) String label, boolean primary) {
}
