package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.MfaRuleException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.model.MfaEnrolmentCode;
import com.xavier.backend.identity.model.MfaFactor;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.dto.MfaResetView;
import com.xavier.backend.identity.model.dto.UserMfaView;
import com.xavier.backend.identity.repository.MfaEnrolmentCodeRepository;
import com.xavier.backend.identity.service.EnrolmentCodes.IssuedCode;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import com.xavier.backend.identity.repository.MfaRecoveryCodeRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator's view and reset of another account's second factor (D143, A60). {@code USER_MANAGE}, itself
 * sensitive, so the administrator steps up first (D142).
 */
@Service
public class MfaResetService {

    private final AccessPolicy accessPolicy;
    private final UserAccountRepository accounts;
    private final MfaFactorRepository factors;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final MfaEnrolmentCodeRepository enrolmentCodes;
    private final EnrolmentCodes codes;
    private final SessionTermination sessions;
    private final AuditLog auditLog;
    private final Clock clock;

    MfaResetService(AccessPolicy accessPolicy, UserAccountRepository accounts, MfaFactorRepository factors,
                    MfaRecoveryCodeRepository recoveryCodes, MfaEnrolmentCodeRepository enrolmentCodes,
                    EnrolmentCodes codes, SessionTermination sessions, AuditLog auditLog, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.accounts = accounts;
        this.factors = factors;
        this.recoveryCodes = recoveryCodes;
        this.enrolmentCodes = enrolmentCodes;
        this.codes = codes;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * Revokes the account's factor (and with it its recovery codes), drops a pending enrolment, voids an open
     * enrolment code, issues a new one (24 h, shown once), ends the account's sessions, and audits {@code MFA_RESET}
     * with the reason. Works on an account without a factor too: that is how a support account gets the code of its
     * first enrolment (D145). A repeat issues a new code and voids the earlier one.
     *
     * @throws UserNotFoundException 404
     * @throws MfaRuleException      422 {@code MFA_SELF_RESET}, {@code REASON_REQUIRED}
     */
    @Transactional
    public MfaResetView reset(UUID accountId, String reason) {
        accessPolicy.require(Permission.USER_MANAGE);
        Actor admin = accessPolicy.current();
        accounts.lockById(accountId).orElseThrow(() -> new UserNotFoundException(accountId));
        if (admin.userId().equals(accountId)) {
            throw MfaRuleException.selfReset();
        }
        if (reason == null || reason.isBlank()) {
            throw MfaRuleException.reasonRequired();
        }
        Instant now = clock.instant();
        Optional<MfaFactor> active = factors.lockByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE);
        active.ifPresent(factor -> {
            factor.revoke(admin.userId(), reason.strip(), now);
            factors.saveAndFlush(factor);
        });
        factors.deletePending(accountId);
        IssuedCode issued = codes.issue(accountId, admin.userId(), reason.strip(), now);
        int ended = sessions.endAccountSessions(accountId);
        auditLog.recordAsCurrentActor("MFA_RESET", "UserAccount", accountId.toString(), null, null, reason.strip(),
                Map.of("factorRevoked", active.isPresent(), "sessionsEnded", ended));
        return new MfaResetView(issued.code(), issued.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserMfaView statusOf(UUID accountId) {
        accessPolicy.require(Permission.USER_MANAGE);
        if (!accounts.existsById(accountId)) {
            throw new UserNotFoundException(accountId);
        }
        Instant now = clock.instant();
        Instant codeExpiresAt = enrolmentCodes.findByAccountIdAndUsedAtIsNull(accountId)
                .map(MfaEnrolmentCode::getExpiresAt).filter(now::isBefore).orElse(null);
        return factors.findByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)
                .map(factor -> new UserMfaView(accountId, true, factor.getActivatedAt(),
                        recoveryCodes.countByFactorIdAndUsedAtIsNull(factor.getId()),
                        factor.isLocked(now) ? factor.getLockedUntil() : null, codeExpiresAt))
                .orElseGet(() -> new UserMfaView(accountId, false, null, 0, null, codeExpiresAt));
    }
}
