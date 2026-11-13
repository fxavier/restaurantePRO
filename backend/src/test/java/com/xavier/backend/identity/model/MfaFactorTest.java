package com.xavier.backend.identity.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.mfa.SecretCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The factor's own rules (D139, D143): replay, lockout, and its PENDING → ACTIVE → REVOKED life. */
class MfaFactorTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    static final UUID ACCOUNT = UUID.fromString("0190b000-0000-7000-8000-000000000011");
    static final UUID ADMIN = UUID.fromString("0190b000-0000-7000-8000-000000000012");
    static final SecretCipher.Sealed SEALED = new SecretCipher.Sealed(new byte[36], new byte[12], "k1");

    @Test
    void aNewFactorIsPendingForTenMinutes() {
        MfaFactor factor = MfaFactor.pending(ACCOUNT, SEALED, NOW);

        assertThat(factor.getStatus()).isEqualTo(MfaFactorStatus.PENDING);
        assertThat(factor.isPendingExpired(NOW.plus(Duration.ofMinutes(10)).minusMillis(1))).isFalse();
        assertThat(factor.isPendingExpired(NOW.plus(Duration.ofMinutes(10)))).isTrue();
    }

    @Test
    void confirmingActivatesItAndUsesTheStep() {
        MfaFactor factor = MfaFactor.pending(ACCOUNT, SEALED, NOW);

        factor.activate(100, NOW);

        assertThat(factor.getStatus()).isEqualTo(MfaFactorStatus.ACTIVE);
        assertThat(factor.getActivatedAt()).isEqualTo(NOW);
        assertThat(factor.getLastUsedStep()).isEqualTo(100);
        assertThat(factor.getPendingExpiresAt()).isNull();
    }

    @Test
    void onlyAPendingFactorIsActivated() {
        MfaFactor factor = active();

        assertThatThrownBy(() -> factor.activate(200, NOW)).isInstanceOf(MfaConflictException.class);
    }

    @Test
    void aStepIsAcceptedOnceAndNeverAnEarlierOne() {
        MfaFactor factor = active();

        assertThat(factor.acceptStep(101, NOW)).isTrue();
        assertThat(factor.acceptStep(101, NOW)).isFalse();
        assertThat(factor.acceptStep(100, NOW)).isFalse();
        assertThat(factor.acceptStep(102, NOW)).isTrue();
        assertThat(factor.getLastUsedStep()).isEqualTo(102);
    }

    @Test
    void theFifthConsecutiveFailureLocksForFifteenMinutes() {
        MfaFactor factor = active();

        for (int i = 1; i <= 4; i++) {
            assertThat(factor.recordFailure(NOW)).as("failure %d locks", i).isFalse();
        }
        assertThat(factor.isLocked(NOW)).isFalse();
        assertThat(factor.recordFailure(NOW)).isTrue();

        assertThat(factor.isLocked(NOW)).isTrue();
        assertThat(factor.lockRemaining(NOW)).isEqualTo(Duration.ofMinutes(15));
        assertThat(factor.isLocked(NOW.plus(Duration.ofMinutes(15)).minusMillis(1))).isTrue();
        assertThat(factor.isLocked(NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void aCorrectCodeResetsTheCount() {
        MfaFactor factor = active();
        for (int i = 0; i < 4; i++) {
            factor.recordFailure(NOW);
        }

        factor.acceptStep(101, NOW);

        assertThat(factor.getFailedAttempts()).isZero();
        assertThat(factor.recordFailure(NOW)).isFalse();
    }

    @Test
    void anAcceptedRecoveryCodeAlsoResetsTheCount() {
        MfaFactor factor = active();
        for (int i = 0; i < 4; i++) {
            factor.recordFailure(NOW);
        }

        factor.succeeded();

        assertThat(factor.getFailedAttempts()).isZero();
    }

    @Test
    void afterTheLockTheCountStartsAgain() {
        MfaFactor factor = active();
        for (int i = 0; i < 5; i++) {
            factor.recordFailure(NOW);
        }
        Instant later = NOW.plus(Duration.ofMinutes(16));

        assertThat(factor.recordFailure(later)).isFalse();
        assertThat(factor.isLocked(later)).isFalse();
    }

    @Test
    void revokingKeepsWhoAndWhy() {
        MfaFactor factor = active();

        factor.revoke(ADMIN, "lost phone", NOW);

        assertThat(factor.getStatus()).isEqualTo(MfaFactorStatus.REVOKED);
        assertThat(factor.getRevokedAt()).isEqualTo(NOW);
        assertThat(factor.getRevokedByAccountId()).isEqualTo(ADMIN);
        assertThat(factor.getRevokeReason()).isEqualTo("lost phone");
        assertThatThrownBy(() -> factor.revoke(ADMIN, "again", NOW)).isInstanceOf(MfaConflictException.class);
    }

    @Test
    void aRevokedFactorAcceptsNothing() {
        MfaFactor factor = active();
        factor.revoke(ADMIN, "lost phone", NOW);

        assertThat(factor.acceptStep(500, NOW)).isFalse();
    }

    private static MfaFactor active() {
        MfaFactor factor = MfaFactor.pending(ACCOUNT, SEALED, NOW);
        factor.activate(100, NOW);
        return factor;
    }
}
