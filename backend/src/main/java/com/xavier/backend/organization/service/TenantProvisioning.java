package com.xavier.backend.organization.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.model.CompanyProfile;
import com.xavier.backend.organization.model.Store;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.organization.repository.CompanyProfileRepository;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.model.event.TenantProvisioned;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * The organization link of onboarding (D181 amended): from {@code TenantProvisioned}, in the event's tenant (the
 * advisor binds it), the tenant's company profile and its first store (code {@value #FIRST_STORE_CODE}), audited,
 * then {@link CompanyProvisioned} in the same transaction. Idempotent: a tenant that already has its profile was
 * provisioned by an earlier delivery, which published {@code CompanyProvisioned} with it, so a redelivery does nothing.
 */
@Service
class TenantProvisioning {

    static final String FIRST_STORE_CODE = "001";
    static final String AUDIT_ACTION = "COMPANY_PROVISIONED";

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioning.class);

    private final CompanyProfileRepository companies;
    private final StoreRepository stores;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final TenantScopes tenants;
    private final Clock clock;
    private final String firstStoreTimezone;

    TenantProvisioning(CompanyProfileRepository companies, StoreRepository stores, AuditLog auditLog,
                       ApplicationEventPublisher events, TenantScopes tenants, Clock clock,
                       @Value("${restaurantpro.provisioning.first-store-timezone:Africa/Maputo}")
                       String firstStoreTimezone) {
        this.companies = companies;
        this.stores = stores;
        this.auditLog = auditLog;
        this.events = events;
        this.tenants = tenants;
        this.clock = clock;
        this.firstStoreTimezone = firstStoreTimezone;
    }

    @ApplicationModuleListener
    @SystemScoped(SystemScope.PROVISIONING)
    void on(TenantProvisioned event) {
        UUID tenantId = tenants.requireTenant();
        if (!tenantId.equals(event.tenantId())) {
            throw new IllegalStateException("TenantProvisioned is handled only in its own tenant (D185)");
        }
        if (companies.existsByTenantId(tenantId)) {
            log.info("Tenant {} already has its company profile; nothing to provision", tenantId);
            return;
        }
        Instant now = clock.instant();
        CompanyProfile company = companies.save(CompanyProfile.create(event.companyName(), event.taxId(),
                event.country(), event.currency(), now));
        Store store = stores.saveAndFlush(Store.create(FIRST_STORE_CODE, event.firstStoreName(), firstStoreTimezone,
                null, now));
        auditLog.recordAsSystem(AUDIT_ACTION, "CompanyProfile", company.getId().toString(), store.getId(), null,
                null, Map.of("country", company.getCountry(), "currency", company.getCurrency().getCurrencyCode()));
        events.publishEvent(new CompanyProvisioned(tenantId, store.getId(), event.adminEmail()));
        log.info("Tenant {} provisioned: company profile and first store {}", tenantId, store.getId());
    }
}
