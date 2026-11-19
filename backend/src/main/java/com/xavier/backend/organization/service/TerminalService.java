package com.xavier.backend.organization.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.StoreNotFoundException;
import com.xavier.backend.organization.exception.TerminalCodeTakenException;
import com.xavier.backend.organization.exception.TerminalNotFoundException;
import com.xavier.backend.organization.model.EnrolmentCode;
import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.dto.EnrollmentCodeResponse;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.organization.repository.TerminalRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.web.PageResponse;
import com.xavier.backend.tenancy.TenancyApi;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store device registration and lifecycle (spec §5, D26, D182, D187): POS terminals and print bridges. Every use case
 * needs {@code TERMINAL_MANAGE} in the device's store. The device redeems its code with {@link DeviceEnrolmentService}.
 */
@Service
public class TerminalService {

    static final Duration ENROLLMENT_CODE_VALIDITY = Duration.ofHours(24);

    private final TerminalRepository terminals;
    private final StoreRepository stores;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TenantScopes tenants;
    private final TenancyApi tenancy;

    TerminalService(TerminalRepository terminals, StoreRepository stores, AccessPolicy accessPolicy,
                    AuditLog auditLog, ApplicationEventPublisher events, Clock clock, TenantScopes tenants,
                    TenancyApi tenancy) {
        this.tenants = tenants;
        this.tenancy = tenancy;
        this.terminals = terminals;
        this.stores = stores;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public TerminalSummary create(TerminalCreateRequest request) {
        accessPolicy.require(Permission.TERMINAL_MANAGE, request.storeId());
        if (!stores.existsById(request.storeId())) {
            throw new StoreNotFoundException(request.storeId());
        }
        String code = Terminal.normalizedCode(request.code());
        if (terminals.existsByStoreIdAndCodeIgnoreCase(request.storeId(), code)) {
            throw new TerminalCodeTakenException(code);
        }
        Terminal terminal = saveNewTerminal(Terminal.register(request.storeId(), request.kind(), request.code(),
                request.name(), clock.instant()));
        audit("TERMINAL_CREATED", terminal);
        return terminal.toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<TerminalSummary> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.TERMINAL_MANAGE, storeId);
        return PageResponse.from(terminals.findByStoreId(storeId, pageable).map(Terminal::toSummary));
    }

    /**
     * A new one-time code for a device that is not revoked (an enrolled one is enrolled again with it: its key was
     * lost), valid {@link #ENROLLMENT_CODE_VALIDITY}. The code's selector becomes the subject of a pending membership
     * of the device's kind, which is how the device's unauthenticated enrolment finds its tenant (D177, D182); an
     * earlier open code is replaced and its pending membership revoked.
     */
    @Transactional
    public EnrollmentCodeResponse issueEnrollmentCode(UUID terminalId) {
        Terminal terminal = terminal(terminalId);
        accessPolicy.require(Permission.TERMINAL_MANAGE, terminal.getStoreId());
        EnrolmentCode code = EnrolmentCode.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ENROLLMENT_CODE_VALIDITY);
        UUID pending = tenancy.grant(new MembershipGrant(DeviceEnrolmentService.ENROLMENT_ISSUER, code.selector(),
                terminal.getKind().membership(), terminal.getId(), ENROLLMENT_CODE_VALIDITY));
        terminal.issueEnrollmentCode(code.secretHash(), expiresAt, pending, now).ifPresent(tenancy::revoke);
        terminals.saveAndFlush(terminal);
        audit("TERMINAL_ENROLLMENT_CODE_ISSUED", terminal);
        return new EnrollmentCodeResponse(terminal.getId(), code.text(), expiresAt);
    }

    /**
     * The device can no longer act: its membership (and an open code's) is revoked in this transaction, so its next
     * request is refused whatever token it holds (D182); {@link TerminalRevoked} then disables its Keycloak client
     * ({@link DeviceClientDisabling}) and ends the POS sessions open on it. Final: register a new device instead.
     */
    @Transactional
    public TerminalSummary revoke(UUID terminalId) {
        Terminal terminal = terminal(terminalId);
        accessPolicy.require(Permission.TERMINAL_MANAGE, terminal.getStoreId());
        Instant now = clock.instant();
        terminal.revoke(now).forEach(tenancy::revoke);
        terminals.saveAndFlush(terminal);
        audit("TERMINAL_REVOKED", terminal);
        events.publishEvent(new TerminalRevoked(tenants.requireTenant(), terminal.getId(), terminal.getStoreId(), now));
        return terminal.toSummary();
    }

    private Terminal saveNewTerminal(Terminal terminal) {
        return UniqueConstraints.saveAndFlush(terminals, terminal,
                () -> new TerminalCodeTakenException(terminal.getCode()), "terminal_store_code_uk");
    }

    private Terminal terminal(UUID id) {
        return terminals.findById(id).orElseThrow(() -> new TerminalNotFoundException(id));
    }

    private void audit(String action, Terminal terminal) {
        auditLog.recordAsCurrentActor(action, "Terminal", terminal.getId().toString(), terminal.getStoreId(), null,
                null, Map.of("code", terminal.getCode()));
    }
}
