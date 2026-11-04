package com.xavier.backend.organization.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.InvalidEnrollmentCodeException;
import com.xavier.backend.organization.exception.StoreNotFoundException;
import com.xavier.backend.organization.exception.TerminalNotFoundException;
import com.xavier.backend.organization.exception.TerminalCodeTakenException;
import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.dto.EnrollmentCodeResponse;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalEnrollmentResponse;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.organization.repository.TerminalRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Terminal registration and lifecycle (spec §5, D26). Every use case needs {@code TERMINAL_MANAGE} in the store. */
@Service
public class TerminalService {

    static final Duration ENROLLMENT_CODE_VALIDITY = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    private final TerminalRepository terminals;
    private final StoreRepository stores;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TerminalService(TerminalRepository terminals, StoreRepository stores, AccessPolicy accessPolicy,
                    AuditLog auditLog, ApplicationEventPublisher events, Clock clock) {
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
        if (terminals.existsByStoreIdAndCode(request.storeId(), request.code().strip())) {
            throw new TerminalCodeTakenException(request.code());
        }
        Terminal terminal = saveNewTerminal(
                Terminal.register(request.storeId(), request.code(), request.name(), clock.instant()));
        audit("TERMINAL_CREATED", terminal, accessPolicy.current().userId());
        return terminal.toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<TerminalSummary> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.TERMINAL_MANAGE, storeId);
        return PageResponse.from(terminals.findByStoreId(storeId, pageable).map(Terminal::toSummary));
    }

    /** A new one-time code for a terminal that is not enrolled yet; valid {@link #ENROLLMENT_CODE_VALIDITY}. */
    @Transactional
    public EnrollmentCodeResponse issueEnrollmentCode(UUID terminalId) {
        Terminal terminal = terminal(terminalId);
        accessPolicy.require(Permission.TERMINAL_MANAGE, terminal.getStoreId());
        String code = TerminalSecrets.newEnrollmentCode();
        Instant expiresAt = clock.instant().plus(ENROLLMENT_CODE_VALIDITY);
        terminal.issueEnrollmentCode(TerminalSecrets.hashEnrollmentCode(code), expiresAt, clock.instant());
        terminals.saveAndFlush(terminal);
        audit("TERMINAL_ENROLLMENT_CODE_ISSUED", terminal, accessPolicy.current().userId());
        return new EnrollmentCodeResponse(terminal.getId(), code, expiresAt);
    }

    /**
     * The device exchanges its one-time code for a device token. No session is needed: the code is the
     * credential. Unknown, expired and used codes all get the same answer, and each refusal is logged (never
     * the code) so repeated guessing is visible.
     *
     * @param remoteAddress where the attempt came from, for the log only
     */
    @Transactional
    public TerminalEnrollmentResponse enroll(String code, String remoteAddress) {
        String codeHash = TerminalSecrets.hashEnrollmentCode(code);
        Instant now = clock.instant();
        Terminal terminal = terminals.findByEnrollmentCodeHash(codeHash)
                .filter(candidate -> candidate.canEnrollWith(codeHash, now))
                .orElse(null);
        if (terminal == null) {
            log.warn("Terminal enrollment refused (unknown, expired or used code) from {}", remoteAddress);
            throw new InvalidEnrollmentCodeException();
        }
        String token = TerminalSecrets.newDeviceToken();
        terminal.enroll(codeHash, TerminalSecrets.hashDeviceToken(token), now);
        terminals.saveAndFlush(terminal); // two devices racing with one code: @Version lets only one win
        audit("TERMINAL_ENROLLED", terminal, null);
        return new TerminalEnrollmentResponse(terminal.getId(), terminal.getStoreId(), token);
    }

    /**
     * The terminal can no longer log in; its device token stops working. Final: enroll a new terminal instead.
     * Publishes {@link TerminalRevoked} so the sessions already open on it end too.
     */
    @Transactional
    public TerminalSummary revoke(UUID terminalId) {
        Terminal terminal = terminal(terminalId);
        accessPolicy.require(Permission.TERMINAL_MANAGE, terminal.getStoreId());
        Instant now = clock.instant();
        terminal.revoke(now);
        terminals.saveAndFlush(terminal);
        audit("TERMINAL_REVOKED", terminal, accessPolicy.current().userId());
        events.publishEvent(new TerminalRevoked(terminal.getId(), terminal.getStoreId(), now));
        return terminal.toSummary();
    }

    private Terminal saveNewTerminal(Terminal terminal) {
        try {
            return terminals.saveAndFlush(terminal);
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraint.isViolated(e, "terminal_store_code_uk")) {
                throw new TerminalCodeTakenException(terminal.getCode());
            }
            throw e;
        }
    }

    private Terminal terminal(UUID id) {
        return terminals.findById(id).orElseThrow(() -> new TerminalNotFoundException(id));
    }

    private void audit(String action, Terminal terminal, UUID actorId) {
        auditLog.record(new AuditEntry(actorId, null, action, "Terminal", terminal.getId().toString(),
                terminal.getStoreId(), terminal.getId(), null, Map.of("code", terminal.getCode())));
    }
}
