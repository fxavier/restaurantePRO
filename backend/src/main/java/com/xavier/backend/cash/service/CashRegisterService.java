package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.RegisterCodeTakenException;
import com.xavier.backend.cash.model.CashRegister;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.model.dto.RegisterCreateRequest;
import com.xavier.backend.cash.model.dto.RegisterSummary;
import com.xavier.backend.cash.model.dto.RegisterUpdateRequest;
import com.xavier.backend.cash.repository.CashRegisterRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Register configuration (BackOffice): {@code CASH_REGISTER_MANAGE} in the register's store. */
@Service
public class CashRegisterService {

    static final String ENTITY = "CashRegister";

    private final CashRegisterRepository registers;
    private final CashSessionRepository sessions;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final CashAudit audit;
    private final Clock clock;

    CashRegisterService(CashRegisterRepository registers, CashSessionRepository sessions,
                        OrganizationApi organization, AccessPolicy accessPolicy, CashAudit audit, Clock clock) {
        this.registers = registers;
        this.sessions = sessions;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RegisterSummary create(RegisterCreateRequest request) {
        accessPolicy.require(Permission.CASH_REGISTER_MANAGE, request.storeId());
        if (!organization.storeExists(request.storeId())) {
            throw new CashNotFoundException("Store", request.storeId());
        }
        CashRegister register = save(
                CashRegister.create(request.storeId(), request.code(), request.name(), clock.instant()));
        record("CASH_REGISTER_CREATED", register);
        return register.toSummary();
    }

    @Transactional
    public RegisterSummary update(UUID id, RegisterUpdateRequest request) {
        accessPolicy.require(Permission.CASH_REGISTER_MANAGE,
                registers.findStoreIdById(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id)));
        CashRegister register = registers.findById(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
        OptimisticLocking.requireVersion(CashRegister.class, id, register.getVersion(), request.version());
        if (register.isActive() && !request.active()
                && sessions.existsByRegisterIdAndStatus(id, CashSessionStatus.OPEN)) {
            throw new CashRuleViolationException(CashRule.REGISTER_IN_USE,
                    "The cash register " + register.getCode() + " has an open session.");
        }
        register.update(request.code(), request.name(), request.active(), clock.instant());
        save(register); // flush now: a concurrent writer fails here (409), and the response has the new version
        record("CASH_REGISTER_UPDATED", register);
        return register.toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<RegisterSummary> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.CASH_REGISTER_MANAGE, storeId);
        return PageResponse.from(registers.findByStoreId(storeId, pageable).map(CashRegister::toSummary));
    }

    private CashRegister save(CashRegister register) {
        return UniqueConstraints.saveAndFlush(registers, register,
                () -> new RegisterCodeTakenException(register.getCode()), "cash_register_store_code_uk");
    }

    private void record(String action, CashRegister register) {
        audit.record(action, ENTITY, register.getId(), register.getStoreId(),
                Map.of("code", register.getCode(), "active", register.isActive()));
    }
}
