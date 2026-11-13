package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.exception.MfaRuleException;
import com.xavier.backend.identity.mfa.Base32;
import com.xavier.backend.identity.mfa.MfaCodes;
import com.xavier.backend.identity.mfa.SecretCipher;
import com.xavier.backend.identity.model.MfaFactor;
import com.xavier.backend.identity.model.MfaFactorStatus;
import com.xavier.backend.identity.model.MfaRecoveryCode;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaEnrolledView;
import com.xavier.backend.identity.model.dto.MfaEnrolmentView;
import com.xavier.backend.identity.model.dto.MfaSessionView;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.MfaStatusView;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.MfaFactorRepository;
import com.xavier.backend.identity.repository.MfaRecoveryCodeRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.organization.OrganizationApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A BackOffice account's own second factor (D139, D143): its status, enrolment (start and confirm) and its recovery
 * codes. Enrolment, confirmation and an administrator's reset of one account are serialized on the account row.
 * Responses that carry a secret or codes are never stored by the idempotency mechanism (the endpoints take no key),
 * and nothing secret is logged or audited.
 */
@Service
public class MfaEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(MfaEnrolmentService.class);

    /** The confirmed factor's codes and the session the account gets, VERIFIED. */
    public record Enrolled(MfaEnrolledView view, Instant secondFactorAt) {
    }

    private final AccessPolicy accessPolicy;
    private final UserAccountRepository accounts;
    private final MfaFactorRepository factors;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final EnrolmentCodes enrolmentCodes;
    private final MfaFactorCheck check;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final LoginService logins;
    private final MfaPolicy policy;
    private final OrganizationApi organization;
    private final AuditLog auditLog;
    private final Clock clock;

    MfaEnrolmentService(AccessPolicy accessPolicy, UserAccountRepository accounts, MfaFactorRepository factors,
                        MfaRecoveryCodeRepository recoveryCodes, EnrolmentCodes enrolmentCodes,
                        MfaFactorCheck check, SecretCipher cipher, PasswordEncoder encoder, LoginService logins,
                        MfaPolicy policy, OrganizationApi organization, AuditLog auditLog, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.accounts = accounts;
        this.factors = factors;
        this.recoveryCodes = recoveryCodes;
        this.enrolmentCodes = enrolmentCodes;
        this.check = check;
        this.cipher = cipher;
        this.encoder = encoder;
        this.logins = logins;
        this.policy = policy;
        this.organization = organization;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** {@code GET /auth/mfa}. @param secondFactorAt the session's {@code MFA_SECOND_FACTOR_AT}, or {@code null} */
    @Transactional(readOnly = true)
    public MfaStatusView status(Instant secondFactorAt) {
        UUID accountId = account().userId();
        return factors.findByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)
                .map(factor -> new MfaStatusView(policy.mode(), true, factor.getActivatedAt(),
                        recoveryCodes.countByFactorIdAndUsedAtIsNull(factor.getId()), validUntil(secondFactorAt)))
                .orElseGet(() -> new MfaStatusView(policy.mode(), false, null, 0, validUntil(secondFactorAt)));
    }

    /**
     * Starts an enrolment: a new secret in a PENDING factor (replacing a PENDING one), valid 10 minutes.
     *
     * @param remote the request is remote (D140): then, as for a support account, an enrolment code is needed and
     *               used up here
     * @throws MfaRefusedException  401 {@code WRONG_CURRENT_PASSWORD}; 403 {@code MFA_ENROLMENT_CODE_REQUIRED}
     * @throws MfaRuleException     422 {@code MFA_ENROLMENT_CODE_INVALID}
     * @throws MfaConflictException 409 {@code MFA_ALREADY_ENROLLED}, {@code MFA_NOT_CONFIGURED}
     */
    @Transactional
    public MfaEnrolmentView start(String currentPassword, String enrolmentCode, boolean remote) {
        UUID accountId = account().userId();
        if (!cipher.configured()) {
            throw MfaConflictException.notConfigured();
        }
        UserAccount account = accounts.lockById(accountId).filter(UserAccount::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        if (!encoder.matches(currentPassword, account.getPasswordHash())) {
            log.warn("MFA enrolment refused for user {}: wrong current password", accountId);
            throw MfaRefusedException.wrongCurrentPassword();
        }
        if (factors.existsByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)) {
            throw MfaConflictException.alreadyEnrolled();
        }
        Instant now = clock.instant();
        boolean withCode = MfaPolicy.enrolmentNeedsCode(remote, account.isSupport());
        if (withCode) {
            useEnrolmentCode(accountId, enrolmentCode, now);
        }
        factors.deletePending(accountId);
        byte[] secret = MfaCodes.newSecret();
        try {
            MfaFactor factor = factors.saveAndFlush(MfaFactor.pending(accountId, cipher.seal(secret, accountId), now));
            auditLog.recordAsCurrentActor("MFA_ENROLMENT_STARTED", "UserAccount", accountId.toString(), null, null,
                    null, Map.of("withEnrolmentCode", withCode));
            String base32 = Base32.encode(secret);
            return new MfaEnrolmentView(base32,
                    MfaCodes.otpauthUri(organization.companyProfile().name(), account.getUsername(), base32),
                    factor.getPendingExpiresAt());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Activates the PENDING factor with its first code, and issues its 10 recovery codes (shown once).
     *
     * @throws MfaConflictException 409 {@code MFA_NO_PENDING_ENROLMENT} (none, or older than 10 minutes)
     * @throws MfaRefusedException  401 {@code MFA_INVALID_CODE}, 429 {@code MFA_LOCKED}
     */
    @Transactional(noRollbackFor = MfaRefusedException.class)
    public Enrolled confirm(int code) {
        UUID accountId = account().userId();
        UserAccount account = accounts.lockById(accountId).filter(UserAccount::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        Instant now = clock.instant();
        MfaFactor factor = factors.lockByAccountIdAndStatus(accountId, MfaFactorStatus.PENDING)
                .filter(pending -> !pending.isPendingExpired(now))
                .orElseThrow(MfaConflictException::noPendingEnrolment);
        if (factor.isLocked(now)) {
            throw MfaRefusedException.locked(factor.lockRemaining(now));
        }
        OptionalLong step = check.matchingStep(factor, code, now);
        if (step.isEmpty()) {
            check.refuse(factor, "ENROLMENT", now);
        }
        factor.activate(step.getAsLong(), now);
        factors.saveAndFlush(factor);
        List<String> codes = issueRecoveryCodes(factor);
        auditLog.recordAsCurrentActor("MFA_ENROLLED", "UserAccount", accountId.toString(), null, null, null,
                Map.of("factorId", factor.getId()));
        SessionView session = logins.sessionOf(account)
                .withMfa(MfaSessionView.of(MfaState.VERIFIED, now, policy.stepUpMaxAge()));
        return new Enrolled(new MfaEnrolledView(codes, session), now);
    }

    /**
     * Replaces every recovery code; needs a second factor proved within the step-up max age, whatever the mode.
     *
     * @param secondFactorAt the session's {@code MFA_SECOND_FACTOR_AT}, or {@code null}
     * @throws MfaConflictException     409 {@code MFA_NOT_ENROLLED}
     * @throws StepUpRequiredException 403 {@code MFA_STEP_UP_REQUIRED}
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(Instant secondFactorAt) {
        UUID accountId = account().userId();
        MfaFactor factor = factors.lockByAccountIdAndStatus(accountId, MfaFactorStatus.ACTIVE)
                .orElseThrow(MfaConflictException::notEnrolled);
        if (secondFactorAt == null || secondFactorAt.plus(policy.stepUpMaxAge()).isBefore(clock.instant())) {
            throw StepUpRequiredException.stepUpRequired();
        }
        recoveryCodes.deleteByFactor(factor.getId());
        List<String> codes = issueRecoveryCodes(factor);
        auditLog.recordAsCurrentActor("MFA_RECOVERY_CODES_REGENERATED", "UserAccount", accountId.toString(), null,
                null, null, Map.of("factorId", factor.getId()));
        return codes;
    }

    private List<String> issueRecoveryCodes(MfaFactor factor) {
        List<String> codes = new ArrayList<>(MfaCodes.RECOVERY_CODES_PER_ENROLMENT);
        List<MfaRecoveryCode> rows = new ArrayList<>(MfaCodes.RECOVERY_CODES_PER_ENROLMENT);
        while (codes.size() < MfaCodes.RECOVERY_CODES_PER_ENROLMENT) {
            String code = MfaCodes.newRecoveryCode();
            if (!codes.contains(code)) {
                codes.add(code);
                rows.add(new MfaRecoveryCode(factor.getId(),
                        cipher.codeHash(SecretCipher.CodePurpose.RECOVERY_CODE, code), cipher.keyId()));
            }
        }
        recoveryCodes.saveAllAndFlush(rows);
        return codes;
    }

    private void useEnrolmentCode(UUID accountId, String enrolmentCode, Instant now) {
        if (enrolmentCode == null || enrolmentCode.isBlank()) {
            throw MfaRefusedException.enrolmentCodeRequired();
        }
        if (!enrolmentCodes.use(accountId, enrolmentCode, now)) {
            log.warn("MFA enrolment refused for user {}: the enrolment code is not valid", accountId);
            throw MfaRuleException.enrolmentCodeInvalid();
        }
    }

    private Actor account() {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.ACCOUNT) {
            throw new AccessDeniedException("Only a BackOffice account has a second factor.");
        }
        return actor;
    }

    private Instant validUntil(Instant secondFactorAt) {
        return secondFactorAt == null ? null : secondFactorAt.plus(policy.stepUpMaxAge());
    }
}
