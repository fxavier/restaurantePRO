package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A delivery address typed at the POS (or read back from a delivery's snapshot). */
public record AddressRequest(@NotBlank @Size(max = 200) String line1, @Size(max = 200) String line2,
                             @Size(max = 100) String city, @Size(max = 20) String postalCode,
                             @Size(max = AddressRequest.MAX_NOTES) String notes) {

    /** Typed notes are capped; a saved address's notes (unbounded in customers) are copied whole. */
    public static final int MAX_NOTES = 2000;
}
