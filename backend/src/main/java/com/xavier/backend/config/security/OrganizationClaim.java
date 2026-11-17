package com.xavier.backend.config.security;

import com.xavier.backend.tenancy.model.dto.MembershipSummary;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * D177 step 8: whether a token's {@code organization} claim names the resolved tenant's Keycloak organization. Keycloak
 * writes the claim as a list of organization aliases, or as a map of alias to attributes (with {@code id} when the
 * mapper adds it); the alias of a tenant's organization is the tenant's code ({@code IdentityProviderAdmin}). The answer
 * is only ever logged and counted: a claim never grants or changes anything.
 */
final class OrganizationClaim {

    private OrganizationClaim() {
    }

    /** {@code true} when {@code claim} is present and names no organization of {@code tenant}. */
    static boolean mismatches(Object claim, MembershipSummary tenant) {
        if (claim == null) {
            return false;
        }
        if (claim instanceof Map<?, ?> organizations) {
            return organizations.entrySet().stream().noneMatch(entry -> tenant.code().equals(entry.getKey())
                    || entry.getValue() instanceof Map<?, ?> attributes && tenant.keycloakOrgId() != null
                    && Objects.equals(tenant.keycloakOrgId(), attributes.get("id")));
        }
        if (claim instanceof Collection<?> aliases) {
            return !aliases.contains(tenant.code());
        }
        return !tenant.code().equals(claim);
    }
}
