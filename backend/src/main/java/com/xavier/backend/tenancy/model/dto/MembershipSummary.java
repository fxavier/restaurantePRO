package com.xavier.backend.tenancy.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.util.Objects;
import java.util.UUID;

/**
 * One ACTIVE, currently valid membership of a subject with its tenant's name, as {@code tenancy.list_memberships}
 * returns it: the tenant list of {@code GET /auth/tenants} and the organization-claim check of D177 step 8.
 *
 * @param keycloakOrgId the tenant's Keycloak organization, {@code null} until onboarding created it
 * @param tenantStatus  not filtered: the caller shows or refuses a SUSPENDED or CLOSED tenant
 */
public record MembershipSummary(UUID tenantId, String code, String legalName, MembershipKind kind,
                                String keycloakOrgId, TenantStatus tenantStatus) {

    public MembershipSummary {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tenantStatus, "tenantStatus");
    }
}
