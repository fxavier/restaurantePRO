package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The customer's identity as edited, with the {@code version} it was read at (D17). */
public record CustomerUpdateRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 30) String taxId,
        @Email @Size(max = 254) String email,
        String notes,
        boolean active,
        long version) {
}
