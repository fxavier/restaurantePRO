package com.xavier.backend.identity.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.mfa.MfaCodes;
import com.xavier.backend.identity.mfa.SecretCipher;
import com.xavier.backend.identity.mfa.Totp;
import com.xavier.backend.identity.model.MfaFactor;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.dto.MfaCodeRequest;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import com.xavier.backend.identity.repository.MfaRecoveryCodeRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checks a code or recovery code against an account's ACTIVE factor (D139), inside the caller's transaction, which
 * must commit even when the check fails ({@code noRollbackFor = MfaRefusedException.class}) so failures count. Not
 * transactional itself on purpose: a participating {@code @Transactional} would mark the transaction rollback-only
 * when it throws. The
 * factor row is locked first, so two sessions using one code at once are serialized and the second is refused. A
 * locked factor refuses even a right code (429); the fifth consecutive failure locks it for 15 minutes, audited once
 * ({@code MFA_LOCKED}). Success is audited {@code MFA_VERIFIED} with {@code kind} (LOGIN, STEP_UP) and {@code method}.
 * Nothing secret is logged or audited.
 */
@Component
class MfaFactorCheck {

    private static final Logger log = LoggerFactory.getLogger(MfaFactorCheck.class);

    private final MfaFactorRepository factors;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final SecretCipher cipher;
    private final AuditLog auditLog;

    MfaFactorCheck(MfaFactorRepository factors, MfaRecoveryCodeRepository recoveryCodes, SecretCipher cipher,
                   AuditLog auditLog) {
        this.factors = factors;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.auditLog = auditLog;
    }

    /**
     * @param kind {@code LOGIN} or {@code STEP_UP}, for the audit
     * @throws MfaConflictException 409 {@code MFA_NOT_ENROLLED} when the account has no ACTIVE factor
     * @throws MfaRefusedException  401 {@code MFA_INVALID_CODE}, 429 {@code MFA_LOCKED}
     */
    void check(UUID accountId, MfaCodeRequest proof, String kind, Instant now) {
        MfaFactor factor = factors.lockByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)
                .orElseThrow(MfaConflictException::notEnrolled);
        if (factor.isLocked(now)) {
            log.warn("Second factor of user {} refused: locked until {}", accountId, factor.getLockedUntil());
            throw MfaRefusedException.locked(factor.lockRemaining(now));
        }
        boolean totp = proof.code() != null;
        boolean accepted = totp ? acceptTotp(factor, proof.totp(), now) : acceptRecoveryCode(factor, proof, now);
        if (!accepted) {
            refuse(factor, kind, now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("method", totp ? "TOTP" : "RECOVERY_CODE");
        auditLog.recordAsCurrentActor("MFA_VERIFIED", "UserAccount", accountId.toString(), null, null, null, details);
        if (!totp) {
            auditLog.recordAsCurrentActor("MFA_RECOVERY_CODE_USED", "UserAccount", accountId.toString(), null, null,
                    null, Map.of("kind", kind, "recoveryCodesLeft",
                            recoveryCodes.countByFactorIdAndUsedAtIsNull(factor.getId())));
        }
    }

    /**
     * Counts a failed check on a factor the caller already holds locked (also a PENDING one at confirmation).
     *
     * @throws MfaRefusedException always: 429 {@code MFA_LOCKED} when this failure locks it, else 401
     */
    void refuse(MfaFactor factor, String kind, Instant now) {
        if (factor.recordFailure(now)) {
            factors.saveAndFlush(factor);
            log.warn("Second factor of user {} locked until {} after {} wrong codes", factor.getAccountId(),
                    factor.getLockedUntil(), MfaFactor.MAX_FAILED_ATTEMPTS);
            auditLog.recordAsCurrentActor("MFA_LOCKED", "UserAccount", factor.getAccountId().toString(), null, null,
                    null, Map.of("kind", kind, "lockedUntil", factor.getLockedUntil().toString()));
            throw MfaRefusedException.locked(factor.lockRemaining(now));
        }
        factors.saveAndFlush(factor);
        log.warn("Second factor of user {} refused: wrong code ({} of {})", factor.getAccountId(),
                factor.getFailedAttempts(), MfaFactor.MAX_FAILED_ATTEMPTS);
        throw MfaRefusedException.invalidCode();
    }

    /** The step of a code that matches the factor's secret and is later than its last used one, if any. */
    OptionalLong matchingStep(MfaFactor factor, int code, Instant now) {
        byte[] secret = cipher.open(factor.sealed(), factor.getAccountId());
        try {
            return Totp.matchingStep(secret, code, Totp.stepAt(now), factor.getLastUsedStep());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private boolean acceptTotp(MfaFactor factor, int code, Instant now) {
        OptionalLong step = matchingStep(factor, code, now);
        return step.isPresent() && factor.acceptStep(step.getAsLong(), now);
    }

    private boolean acceptRecoveryCode(MfaFactor factor, MfaCodeRequest proof, Instant now) {
        if (recoveryCodes.consume(factor.getId(), MfaCodes.hash(proof.recoveryCode()), now) != 1) {
            return false;
        }
        factor.succeeded();
        return true;
    }
}
