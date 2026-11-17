package com.xavier.backend.tenancy.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/platform/tenants} (D181): a new company, its first store and its first administrator.
 *
 * @param code       the tenant's stable code and Keycloak organization alias; onboarding is idempotent by it
 * @param adminEmail invited by identity (S72); never logged
 */
public record OnboardTenantRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String code,
        @NotBlank @Size(max = 200) String legalName,
        @NotBlank @Size(max = 50) String taxId,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 100) String firstStoreName,
        @NotBlank @Email @Size(max = 254) String adminEmail) {
}
