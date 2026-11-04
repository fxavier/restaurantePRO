package com.xavier.backend.organization.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.CompanyNotBootstrappedException;
import com.xavier.backend.organization.exception.StoreCodeTakenException;
import com.xavier.backend.organization.exception.StoreNotFoundException;
import com.xavier.backend.organization.model.CompanyProfile;
import com.xavier.backend.organization.model.Store;
import com.xavier.backend.organization.model.dto.StoreCreateRequest;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.StoreUpdateRequest;
import com.xavier.backend.organization.repository.CompanyProfileRepository;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store use cases (spec §5). Creating, editing and listing stores needs installation-wide
 * {@code STORE_MANAGE}; reading one store also accepts {@code STORE_MANAGE} in that store.
 */
@Service
public class StoreService {

    private final StoreRepository stores;
    private final CompanyProfileRepository companies;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    StoreService(StoreRepository stores, CompanyProfileRepository companies, AccessPolicy accessPolicy,
                 AuditLog auditLog, Clock clock) {
        this.stores = stores;
        this.companies = companies;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public StoreSummary create(StoreCreateRequest request) {
        accessPolicy.require(Permission.STORE_MANAGE);
        Currency currency = companyCurrency();
        if (stores.existsByCode(request.code().strip())) {
            throw new StoreCodeTakenException(request.code());
        }
        Store store = saveWithUniqueCode(Store.create(request.code(), request.name(), request.timezone(),
                request.businessDayCutoff(), clock.instant()));
        audit("STORE_CREATED", store);
        return store.toSummary(currency);
    }

    @Transactional
    public StoreSummary update(UUID id, StoreUpdateRequest request) {
        accessPolicy.require(Permission.STORE_MANAGE);
        Store store = store(id);
        OptimisticLocking.requireVersion(Store.class, id, store.getVersion(), request.version());
        if (stores.existsByCodeAndIdNot(request.code().strip(), id)) {
            throw new StoreCodeTakenException(request.code());
        }
        store.update(request.code(), request.name(), request.timezone(), request.businessDayCutoff(),
                request.active(), clock.instant());
        saveWithUniqueCode(store); // flush now: a concurrent writer fails here (409), and the response has the new version
        audit("STORE_UPDATED", store);
        return store.toSummary(companyCurrency());
    }

    @Transactional(readOnly = true)
    public StoreSummary get(UUID id) {
        accessPolicy.require(Permission.STORE_MANAGE, id);
        return store(id).toSummary(companyCurrency());
    }

    @Transactional(readOnly = true)
    public PageResponse<StoreSummary> list(Pageable pageable) {
        accessPolicy.require(Permission.STORE_MANAGE);
        Currency currency = companyCurrency();
        return PageResponse.from(stores.findAll(pageable).map(store -> store.toSummary(currency)));
    }

    private Store saveWithUniqueCode(Store store) {
        return UniqueConstraints.saveAndFlush(stores, store,
                () -> new StoreCodeTakenException(store.getCode()), "store_code_uk");
    }

    private Store store(UUID id) {
        return stores.findById(id).orElseThrow(() -> new StoreNotFoundException(id));
    }

    private void audit(String action, Store store) {
        auditLog.record(new AuditEntry(accessPolicy.current().userId(), null, action, "Store",
                store.getId().toString(), store.getId(), null, null, Map.of("code", store.getCode())));
    }

    private Currency companyCurrency() {
        return companies.findById(Boolean.TRUE).map(CompanyProfile::getCurrency)
                .orElseThrow(CompanyNotBootstrappedException::new);
    }
}
