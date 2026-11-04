package com.xavier.backend.organization.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.CompanyNotBootstrappedException;
import com.xavier.backend.organization.model.CompanyProfile;
import com.xavier.backend.organization.model.dto.CompanyProfileUpdateRequest;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.repository.CompanyProfileRepository;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and edits the single company profile (spec §5, D30). There is no create: bootstrap does that. */
@Service
public class CompanyProfileService {

    private final CompanyProfileRepository companies;
    private final StoreRepository stores;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    CompanyProfileService(CompanyProfileRepository companies, StoreRepository stores, AccessPolicy accessPolicy,
                          AuditLog auditLog, Clock clock) {
        this.companies = companies;
        this.stores = stores;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CompanyProfileView get() {
        accessPolicy.require(Permission.COMPANY_MANAGE);
        return company().toView();
    }

    @Transactional
    public CompanyProfileView update(CompanyProfileUpdateRequest request) {
        accessPolicy.require(Permission.COMPANY_MANAGE);
        CompanyProfile company = company();
        OptimisticLocking.requireVersion(CompanyProfile.class, Boolean.TRUE, company.getVersion(), request.version());
        company.update(request.name(), request.taxId(), request.country(), request.currency(), stores.count() > 0,
                clock.instant());
        companies.saveAndFlush(company); // flush now: the response carries the new version
        auditLog.recordAsCurrentActor("COMPANY_PROFILE_UPDATED", "CompanyProfile",
                CompanyBootstrapService.AUDIT_ENTITY_ID, null, null, null,
                Map.of("country", company.getCountry(), "currency", company.getCurrency().getCurrencyCode()));
        return company.toView();
    }

    private CompanyProfile company() {
        return companies.findById(Boolean.TRUE).orElseThrow(CompanyNotBootstrappedException::new);
    }
}
