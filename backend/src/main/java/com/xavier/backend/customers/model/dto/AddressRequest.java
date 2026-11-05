package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A new address of a customer; {@code kind} is BILLING or DELIVERY. */
public record AddressRequest(
        @NotNull @Pattern(regexp = "BILLING|DELIVERY") String kind,
        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @Size(max = 100) String city,
        @Size(max = 20) String postalCode,
        @Size(max = 60) String country,
        String notes) {
}
