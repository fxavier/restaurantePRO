package com.xavier.backend.identity.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.tenancy.model.dto.MembershipSummary;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * One of the caller's own memberships ({@code GET /auth/tenants}, D177 amended): a tenant the caller may select with
 * {@code X-Tenant-Id}, named by its code and legal name, with its status (a suspended tenant is shown, and refused when
 * selected). Never the tenant's Keycloak organization.
 */
@NullMarked
public record TenantChoice(UUID tenantId, String code, String legalName, MembershipKind kind, TenantStatus status) {

    public static TenantChoice from(MembershipSummary membership) {
        return new TenantChoice(membership.tenantId(), membership.code(), membership.legalName(), membership.kind(),
                membership.tenantStatus());
    }
}
