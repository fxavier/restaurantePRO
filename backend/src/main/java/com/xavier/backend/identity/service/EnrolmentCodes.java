package com.xavier.backend.identity.service;

import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.mfa.MfaCodes;
import com.xavier.backend.identity.mfa.SecretCipher;
import com.xavier.backend.identity.model.MfaEnrolmentCode;
import com.xavier.backend.identity.repository.MfaEnrolmentCodeRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Enrolment codes (D143), stored as keyed hashes (D139 amended): issue one, find an account's open one that matches a
 * given code (compared in constant time), and use it once (compare-and-set on {@code used_at}).
 */
@Component
class EnrolmentCodes {

    private final MfaEnrolmentCodeRepository codes;
    private final SecretCipher cipher;

    EnrolmentCodes(MfaEnrolmentCodeRepository codes, SecretCipher cipher) {
        this.codes = codes;
        this.cipher = cipher;
    }

    /** The account's open, unexpired code, if {@code code} is it. */
    Optional<MfaEnrolmentCode> open(UUID accountId, String code, Instant now) {
        if (code == null || code.isBlank() || !cipher.configured()) {
            return Optional.empty();
        }
        return codes.findByAccountIdAndUsedAtIsNull(accountId)
                .filter(open -> open.isOpen(now))
                .filter(open -> cipher.matches(SecretCipher.CodePurpose.ENROLMENT_CODE, code, open.getCodeHash()));
    }

    /** @return whether {@code code} was the account's open code and is now used */
    boolean use(UUID accountId, String code, Instant now) {
        return open(accountId, code, now).map(open -> codes.consume(open.getId(), now) == 1).orElse(false);
    }

    /**
     * Voids the account's open code and issues a new one (24 h).
     *
     * @return the code, to hand over once
     * @throws MfaConflictException 409 {@code MFA_NOT_CONFIGURED} without an MFA key
     */
    IssuedCode issue(UUID accountId, UUID issuedBy, String reason, Instant now) {
        if (!cipher.configured()) {
            throw MfaConflictException.notConfigured();
        }
        codes.voidOpen(accountId, now);
        String code = MfaCodes.newEnrolmentCode();
        MfaEnrolmentCode issued = codes.saveAndFlush(MfaEnrolmentCode.issue(accountId,
                cipher.codeHash(SecretCipher.CodePurpose.ENROLMENT_CODE, code), cipher.keyId(), issuedBy, reason,
                now));
        return new IssuedCode(code, issued.getExpiresAt());
    }

    record IssuedCode(String code, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedCode[expiresAt=" + expiresAt + "]";
        }
    }
}
