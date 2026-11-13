package com.xavier.backend.identity.model;

import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.mfa.SecretCipher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A BackOffice account's TOTP second factor (D139). The secret is kept sealed ({@link SecretCipher}); this entity
 * never sees it in clear. Its rules: a code is accepted only for a TOTP step later than the last one used (replay,
 * also across sessions: the caller holds the row lock), 5 consecutive failures lock second-factor checks for 15
 * minutes, and the life is PENDING (10 minutes to confirm) → ACTIVE → REVOKED. One ACTIVE and one PENDING per
 * account (partial unique indexes).
 */
@Entity
@Table(schema = "identity", name = "mfa_factor")
public class MfaFactor {

    public static final String KIND_TOTP = "TOTP";
    public static final Duration PENDING_VALIDITY = Duration.ofMinutes(10);
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK = Duration.ofMinutes(15);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MfaFactorStatus status;

    @Column(nullable = false, updatable = false)
    private byte[] secretCiphertext;

    @Column(nullable = false, updatable = false)
    private byte[] secretNonce;

    @Column(nullable = false, updatable = false, length = 20)
    private String keyId;

    @Column(nullable = false)
    private long lastUsedStep;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    private Instant pendingExpiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant activatedAt;

    private Instant revokedAt;

    private UUID revokedByAccountId;

    @Column(length = 500)
    private String revokeReason;

    @Version
    private Long version;

    protected MfaFactor() {
        // JPA
    }

    /** An enrolment just started: the secret is shown once and must be confirmed within {@link #PENDING_VALIDITY}. */
    public static MfaFactor pending(UUID accountId, SecretCipher.Sealed sealed, Instant now) {
        MfaFactor factor = new MfaFactor();
        factor.accountId = accountId;
        factor.kind = KIND_TOTP;
        factor.status = MfaFactorStatus.PENDING;
        factor.secretCiphertext = sealed.ciphertext().clone();
        factor.secretNonce = sealed.nonce().clone();
        factor.keyId = sealed.keyId();
        factor.pendingExpiresAt = now.plus(PENDING_VALIDITY);
        factor.createdAt = now;
        return factor;
    }

    public boolean isPendingExpired(Instant now) {
        return status != MfaFactorStatus.PENDING || !now.isBefore(pendingExpiresAt);
    }

    /** The first code proved the user holds the secret; {@code step} is that code's step. */
    public void activate(long step, Instant now) {
        if (status != MfaFactorStatus.PENDING) {
            throw MfaConflictException.noPendingEnrolment();
        }
        status = MfaFactorStatus.ACTIVE;
        activatedAt = now;
        pendingExpiresAt = null;
        lastUsedStep = step;
        succeeded();
    }

    /**
     * Uses {@code step} (the step of a code that matched) unless it is not later than the last one used.
     *
     * @return {@code false} for a replayed or older code, or a factor that is no longer usable
     */
    public boolean acceptStep(long step, Instant now) {
        if (status == MfaFactorStatus.REVOKED || step <= lastUsedStep) {
            return false;
        }
        lastUsedStep = step;
        succeeded();
        return true;
    }

    /** A correct code or recovery code: the failure count starts again. */
    public void succeeded() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    /** @return {@code true} when this failure locks the factor (audited once by the caller) */
    public boolean recordFailure(Instant now) {
        failedAttempts++;
        if (failedAttempts < MAX_FAILED_ATTEMPTS) {
            return false;
        }
        failedAttempts = 0;
        lockedUntil = now.plus(LOCK);
        return true;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** How long the lock still holds; {@link Duration#ZERO} when not locked. */
    public Duration lockRemaining(Instant now) {
        return isLocked(now) ? Duration.between(now, lockedUntil) : Duration.ZERO;
    }

    /** An administrator's reset (or the console break-glass, in SQL). Recovery codes die with the factor. */
    public void revoke(UUID byAccountId, String reason, Instant now) {
        if (status != MfaFactorStatus.ACTIVE) {
            throw MfaConflictException.notEnrolled();
        }
        status = MfaFactorStatus.REVOKED;
        revokedAt = now;
        revokedByAccountId = byAccountId;
        revokeReason = reason;
        lockedUntil = null;
    }

    public SecretCipher.Sealed sealed() {
        return new SecretCipher.Sealed(secretCiphertext.clone(), secretNonce.clone(), keyId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public MfaFactorStatus getStatus() {
        return status;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public long getLastUsedStep() {
        return lastUsedStep;
    }

    public Instant getPendingExpiresAt() {
        return pendingExpiresAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedByAccountId() {
        return revokedByAccountId;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
