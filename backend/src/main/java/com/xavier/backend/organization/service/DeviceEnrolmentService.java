package com.xavier.backend.organization.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.exception.EnrolmentThrottledException;
import com.xavier.backend.organization.exception.InvalidEnrollmentCodeException;
import com.xavier.backend.organization.model.DeviceKind;
import com.xavier.backend.organization.model.DevicePublicKey;
import com.xavier.backend.organization.model.EnrolmentCode;
import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.Terminal.Presentation;
import com.xavier.backend.organization.model.dto.DeviceEnrolmentResponse;
import com.xavier.backend.organization.repository.TerminalRepository;
import com.xavier.backend.shared.ExternalCalls;
import com.xavier.backend.shared.exception.UnavailableException;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.MembershipSelectionRequiredException;
import com.xavier.backend.tenancy.TenancyApi;
import com.xavier.backend.tenancy.model.dto.Membership;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A device redeems its one-time code (D182; bridges alike, D187), before it has any token. The code's selector is the
 * subject of a pending membership ({@link #ENROLMENT_ISSUER}, never a token issuer), which names the device and its
 * tenant through {@code tenancy.resolve_membership}: the tenant comes from a membership, never from the request
 * (D177). Then, in that tenant: (1) the secret is checked and the device's key pinned, failures counted and the fifth
 * burning the code — committed before any answer; (2) outside any transaction (D48), the device's client is created
 * (or found) in the devices realm and the key registered on it; (3) the device's own membership is granted to the
 * client's service account and the code marked used, in one transaction. Retrying with the same key after a crash
 * between the steps, or after a lost answer, ends in the same state and the same answer (the used code replays its
 * enrolment for its key until it expires); another key cannot finish an enrolment a first key started. Every refusal is one answer ({@code ENROLMENT_CODE_INVALID}); the code is never logged.
 */
@Service
public class DeviceEnrolmentService {

    /** The issuer of the pending memberships of open codes: no token is ever issued by it, so none can act. */
    public static final String ENROLMENT_ISSUER = "urn:restaurantpro:enrolment-code";

    private static final Logger log = LoggerFactory.getLogger(DeviceEnrolmentService.class);

    private final TerminalRepository terminals;
    private final TenancyApi tenancy;
    private final TenantScopes tenants;
    private final EnrolmentThrottle throttle;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;
    private final String devicesIssuer;
    private final Clock clock;
    private final Counter refusals;
    private final Counter burned;

    DeviceEnrolmentService(TerminalRepository terminals, TenancyApi tenancy, TenantScopes tenants,
                           EnrolmentThrottle throttle, AuditLog auditLog, PlatformTransactionManager transactions,
                           @Value("${restaurantpro.security.issuers.devices:}") String devicesIssuer, Clock clock,
                           MeterRegistry meters) {
        this.refusals = meters.counter("enrolment_refused_total");
        this.burned = meters.counter("enrolment_code_burned_total");
        this.terminals = terminals;
        this.tenancy = tenancy;
        this.tenants = tenants;
        this.throttle = throttle;
        this.auditLog = auditLog;
        this.transaction = new TransactionTemplate(transactions);
        this.devicesIssuer = devicesIssuer;
        this.clock = clock;
    }

    /**
     * @param kind          the device kind the route serves (a terminal's code does not enrol a bridge)
     * @param remoteAddress the request's source address, for the throttle and the refusal log
     */
    public DeviceEnrolmentResponse enrol(DeviceKind kind, String typedCode, Map<String, Object> publicJwk,
                                         String remoteAddress) {
        ExternalCalls.requireNoTransaction();
        if (!throttle.tryAttempt(remoteAddress)) {
            log.warn("Device enrolment throttled for {}", remoteAddress);
            throw new EnrolmentThrottledException();
        }
        DevicePublicKey key = DevicePublicKey.parse(publicJwk);
        EnrolmentCode code = EnrolmentCode.parse(typedCode).orElseThrow(() -> refused(remoteAddress));
        Membership pending = pendingMembership(kind, code).orElseThrow(() -> refused(remoteAddress));
        String thumbprint = key.thumbprint();
        Presentation presented = tenants.call(pending.tenantId(), SystemScope.DEVICE_ENROLMENT,
                () -> transaction.execute(status -> present(pending, kind, code, thumbprint)));
        if (presented == Presentation.REPLAYED) {
            return tenants.call(pending.tenantId(), SystemScope.DEVICE_ENROLMENT,
                    () -> transaction.execute(status -> answer(terminals.findById(pending.principalId()).orElseThrow())));
        }
        if (presented != Presentation.ACCEPTED) {
            throw refused(remoteAddress);
        }
        String clientId = kind.clientId(pending.principalId());
        String subject = tenancy.idp().createClient(clientId);
        tenancy.idp().registerClientKey(clientId, key.jwk());
        return tenants.call(pending.tenantId(), SystemScope.DEVICE_ENROLMENT,
                () -> transaction.execute(status -> complete(pending, kind, subject, thumbprint)));
    }

    private Optional<Membership> pendingMembership(DeviceKind kind, EnrolmentCode code) {
        try {
            return tenancy.resolve(ENROLMENT_ISSUER, code.selector(), null, Set.of(kind.membership()))
                    .filter(membership -> membership.tenantStatus() == TenantStatus.ACTIVE);
        } catch (MembershipSelectionRequiredException collision) {
            return Optional.empty(); // one selector in two tenants: neither is redeemable by it
        }
    }

    private Presentation present(Membership pending, DeviceKind kind, EnrolmentCode code, String thumbprint) {
        Optional<Terminal> device = terminals.lockById(pending.principalId())
                .filter(found -> found.getKind() == kind);
        if (device.isEmpty()) {
            return Presentation.REFUSED;
        }
        Terminal terminal = device.get();
        Optional<UUID> pendingId = terminal.pendingMembership();
        Presentation presented = terminal.presentCode(code.secretHash(), thumbprint, clock.instant());
        terminals.saveAndFlush(terminal);
        if (presented == Presentation.BURNED) {
            burned.increment();
            pendingId.ifPresent(tenancy::revoke);
            log.warn("Enrolment code of device {} burned after {} failed attempts", terminal.getId(),
                    Terminal.MAX_FAILURES);
        }
        return presented;
    }

    private DeviceEnrolmentResponse complete(Membership pending, DeviceKind kind, String subject, String thumbprint) {
        if (devicesIssuer.isBlank()) {
            throw new UnavailableException("IDP_UNAVAILABLE", "The devices realm is not configured.", Map.of());
        }
        Terminal terminal = terminals.lockById(pending.principalId()).orElseThrow(InvalidEnrollmentCodeException::new);
        if (terminal.isEnrolledWith(thumbprint)) {
            return answer(terminal); // the same key's concurrent retry completed it first
        }
        Instant now = clock.instant();
        UUID membership = tenancy.grant(new MembershipGrant(devicesIssuer, subject, kind.membership(),
                terminal.getId(), null));
        if (!terminal.completeEnrolment(thumbprint, membership, now)) {
            throw new InvalidEnrollmentCodeException();
        }
        terminals.saveAndFlush(terminal);
        auditLog.recordAsSystem("TERMINAL_ENROLLED", "Terminal", terminal.getId().toString(), terminal.getStoreId(),
                kind == DeviceKind.POS ? terminal.getId() : null, null,
                Map.of("code", terminal.getCode(), "kind", kind.name()));
        return answer(terminal);
    }

    private static DeviceEnrolmentResponse answer(Terminal terminal) {
        return new DeviceEnrolmentResponse(terminal.getId(), terminal.getStoreId(), terminal.clientId());
    }

    /** Counted for alerting (D149), no tenant label (rule 7); the code itself is never logged. */
    private InvalidEnrollmentCodeException refused(String remoteAddress) {
        refusals.increment();
        log.warn("Device enrolment refused (unknown, expired, used or burned code) from {}", remoteAddress);
        return new InvalidEnrollmentCodeException();
    }
}
