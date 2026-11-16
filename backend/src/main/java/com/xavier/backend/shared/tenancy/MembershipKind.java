package com.xavier.backend.shared.tenancy;

/**
 * How a tenant context was obtained (D177): the membership kind of {@code tenancy.tenant_membership}, or
 * {@link #SYSTEM} for a job, listener or bootstrap bound by {@link TenantScopes} (D185).
 */
public enum MembershipKind {
    ACCOUNT,
    TERMINAL,
    BRIDGE,
    SUPPORT,
    SYSTEM
}
