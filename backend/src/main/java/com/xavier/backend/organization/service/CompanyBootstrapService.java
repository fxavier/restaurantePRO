package com.xavier.backend.organization.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.InvalidCompanyProfileException;
import com.xavier.backend.organization.model.CompanyProfile;
import com.xavier.backend.organization.model.event.CompanyBootstrapped;
import com.xavier.backend.organization.repository.CompanyProfileRepository;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the bound tenant's company at first startup (D30; the bootstrap tenant, D180). Idempotent: once the company
 * exists, later runs do nothing, whatever the settings say; edits go through {@code PUT /backoffice/company-profile}.
 */
@Service
public class CompanyBootstrapService {

    static final String AUDIT_ENTITY_ID = "company-profile";

    private static final Logger log = LoggerFactory.getLogger(CompanyBootstrapService.class);

    private final CompanyProfileRepository companies;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TenantScopes tenants;

    CompanyBootstrapService(CompanyProfileRepository companies, AuditLog auditLog,
                            ApplicationEventPublisher events, Clock clock, TenantScopes tenants) {
        this.tenants = tenants;
        this.companies = companies;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @return {@code true} only when this call created the company
     * @throws IllegalStateException when the settings are present but incomplete or invalid
     */
    @Transactional
    public boolean bootstrap(CompanyBootstrapProperties settings) {
        if (companies.existsByTenantId(tenants.requireTenant())) {
            return false;
        }
        if (settings.isUnset()) {
            log.warn("No company yet and restaurantpro.bootstrap.company.* is not set: "
                    + "set RESTAURANTPRO_BOOTSTRAP_COMPANY_{NAME,TAX_ID,COUNTRY,CURRENCY} and restart");
            return false;
        }
        CompanyProfile company = companies.save(create(settings));
        auditLog.recordAsSystem("COMPANY_BOOTSTRAPPED", "CompanyProfile", AUDIT_ENTITY_ID, null, null, null,
                Map.of("country", company.getCountry(), "currency", company.getCurrency().getCurrencyCode()));
        events.publishEvent(new CompanyBootstrapped(tenants.requireTenant(),
                company.getName(), company.getTaxId(), company.getCountry(),
                company.getCurrency().getCurrencyCode(), company.getUpdatedAt()));
        log.info("Company bootstrapped (country {}, currency {})", company.getCountry(), company.getCurrency());
        return true;
    }

    private CompanyProfile create(CompanyBootstrapProperties settings) {
        try {
            return CompanyProfile.create(settings.name(), settings.taxId(), settings.country(), settings.currency(),
                    clock.instant());
        } catch (InvalidCompanyProfileException e) {
            throw new IllegalStateException(
                    "Invalid restaurantpro.bootstrap.company.* settings: " + e.getMessage(), e);
        }
    }
}
