package com.xavier.backend.identity.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.util.UUID;

/**
 * One of the caller's own memberships ({@code GET /auth/tenants}, D177 amended): the tenants the caller may select
 * with {@code X-Tenant-Id}. S72 lists the id and kind only; the tenant's code and legal name follow when tenancy
 * exposes them (S75, then S73).
 */
public record TenantChoice(UUID tenantId, MembershipKind kind) {
}
