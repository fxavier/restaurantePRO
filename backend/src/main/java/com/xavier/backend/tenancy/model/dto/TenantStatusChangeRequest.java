package com.xavier.backend.tenancy.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Suspension, reactivation or closure of a tenant: why (recorded in the platform audit, D181). */
public record TenantStatusChangeRequest(@NotBlank @Size(max = 500) String reason) {
}
