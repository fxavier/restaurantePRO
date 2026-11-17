package com.xavier.backend.tenancy.model.dto;

import java.util.UUID;

/** What a platform route returns about a tenant: tenancy data only, never business data (D181, D183). */
public record PlatformTenantView(UUID id, String code, TenantStatus status) {
}
