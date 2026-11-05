package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new customer; the POS quick create sends only name, phone and tax id. The phone becomes the primary one. */
public record CustomerCreateRequest(
        @NotBlank @Size(max = 160) String name,
        @PhoneDigits String phone,
        @Size(max = 30) String taxId,
        @Email @Size(max = 254) String email,
        String notes) {
}
