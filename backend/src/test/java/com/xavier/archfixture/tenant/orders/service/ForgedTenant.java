package com.xavier.archfixture.tenant.orders.service;

import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import java.util.UUID;

/** Fixture: a module that makes its own tenant context. */
public class ForgedTenant {

    public TenantContext forge(UUID tenant) {
        return new TenantContext(tenant, MembershipKind.ACCOUNT, UUID.randomUUID(), "forged");
    }
}
