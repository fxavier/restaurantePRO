package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full replacement of the company profile; {@code version} is the one the client read (D17). */
public record CompanyProfileUpdateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String taxId,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull Long version) {
}
