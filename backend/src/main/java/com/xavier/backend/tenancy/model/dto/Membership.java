package com.xavier.backend.tenancy.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.util.Objects;
import java.util.UUID;

/**
 * An ACTIVE membership valid now, as {@code tenancy.resolve_membership} returns it (D177).
 *
 * @param principalId  the account, terminal, bridge or support grant the membership stands for
 * @param tenantStatus the tenant's status: a membership of a SUSPENDED or CLOSED tenant still resolves, and the
 *                     caller refuses it ({@code TENANT_SUSPENDED}, {@code TENANT_CLOSED})
 */
public record Membership(UUID tenantId, MembershipKind kind, UUID principalId, TenantStatus tenantStatus) {

    public Membership {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(tenantStatus, "tenantStatus");
    }
}
