package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.TenancyApi;
import org.springframework.stereotype.Component;

/**
 * Names, on a {@link SessionView}, the tenant the request was resolved in (its membership's {@link TenantContext},
 * D177), never the {@code X-Tenant-Id} the client sent: the BackOffice shows the company the server relays for. A
 * session with a store (a POS session) also gets that store's name, timezone and currency, so the POS shows the
 * store and its times in the store's zone, not the terminal's.
 */
@Component
class SessionContext {

    private final TenantScopes tenants;
    private final TenancyApi tenancy;
    private final OrganizationApi organization;

    SessionContext(TenantScopes tenants, TenancyApi tenancy, OrganizationApi organization) {
        this.tenants = tenants;
        this.tenancy = tenancy;
        this.organization = organization;
    }

    SessionView stamp(SessionView view) {
        return tenants.current().map(TenantContext::tenantId)
                .map(tenant -> withStore(view.withTenant(tenant, tenancy.legalName(tenant))))
                .orElse(view);
    }

    private SessionView withStore(SessionView view) {
        if (view.storeId() == null) {
            return view;
        }
        return organization.findStore(view.storeId())
                .map(store -> view.withStore(store.name(), store.timezone().getId(), store.currency().getCurrencyCode()))
                .orElse(view);
    }
}
