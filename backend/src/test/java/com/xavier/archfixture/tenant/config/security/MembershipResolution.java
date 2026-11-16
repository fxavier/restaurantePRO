package com.xavier.archfixture.tenant.config.security;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import java.util.UUID;

/** Fixture: the resolution, the one place that creates a tenant context from a membership. */
public class MembershipResolution {

    public TenantContext resolved(UUID tenant, UUID principal) {
        return new TenantContext(tenant, MembershipKind.ACCOUNT, principal, "resolved");
    }
}
