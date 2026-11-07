package com.xavier.backend.orders.delivery.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A delivery address typed at the POS (or read back from a delivery's snapshot). */
public record AddressRequest(@NotBlank @Size(max = 160) String line1, @Size(max = 160) String line2,
                             @Size(max = 80) String city, @Size(max = 20) String postalCode,
                             @Size(max = 300) String notes) {
}
