package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.TenancyApi;
import org.springframework.stereotype.Component;

/**
 * Names, on a {@link SessionView}, the tenant the request was resolved in (its membership's {@link TenantContext},
 * D177), never the {@code X-Tenant-Id} the client sent: the BackOffice shows the company the server relays for.
 */
@Component
class SessionTenant {

    private final TenantScopes tenants;
    private final TenancyApi tenancy;

    SessionTenant(TenantScopes tenants, TenancyApi tenancy) {
        this.tenants = tenants;
        this.tenancy = tenancy;
    }

    SessionView stamp(SessionView view) {
        return tenants.current().map(TenantContext::tenantId)
                .map(tenant -> view.withTenant(tenant, tenancy.legalName(tenant)))
                .orElse(view);
    }
}
