package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new supplier (D123), company-wide. */
public record SupplierRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 40) String taxId,
        @Email @Size(max = 200) String email,
        @Size(max = 40) String phone,
        @Size(max = 2000) String notes) {
}
