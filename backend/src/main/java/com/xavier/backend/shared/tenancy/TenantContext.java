package com.xavier.backend.shared.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * The tenant a thread works in (D177): resolved from an authorised membership by {@code config.security}, or bound
 * by {@link TenantScopes} for a system scope, a listener (the event's tenant) or a task. Immutable. Technical only
 * (D14): an id and its origin, never a business type. Created only by {@code config.security},
 * {@link TenantScopes} and test sources (ArchUnit {@code onlyResolutionCreatesTenantContext}).
 */
public final class TenantContext {

    private final UUID tenantId;
    private final MembershipKind kind;
    private final UUID principalId;
    private final String origin;

    /**
     * @param principalId the membership's principal (account, terminal, bridge, support grant); {@code null} only for
     *                    {@link MembershipKind#SYSTEM}
     * @param origin      who the context is for, quoted in logs and errors only (never read by a policy)
     */
    public TenantContext(UUID tenantId, MembershipKind kind, UUID principalId, String origin) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (principalId == null && kind != MembershipKind.SYSTEM) {
            throw new IllegalArgumentException("Only a SYSTEM tenant context has no principal");
        }
        this.principalId = principalId;
        this.origin = Objects.requireNonNull(origin, "origin");
        if (origin.isBlank()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
    }

    public UUID tenantId() {
        return tenantId;
    }

    public MembershipKind kind() {
        return kind;
    }

    public UUID principalId() {
        return principalId;
    }

    public String origin() {
        return origin;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TenantContext that && tenantId.equals(that.tenantId) && kind == that.kind
                && Objects.equals(principalId, that.principalId) && origin.equals(that.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, kind, principalId, origin);
    }

    @Override
    public String toString() {
        return "TenantContext[" + tenantId + ", " + kind + ", " + origin + "]";
    }
}
