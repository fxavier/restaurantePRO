package com.xavier.backend.identity.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.exception.IllegalSupportGrantStateException;
import com.xavier.backend.identity.exception.SupportGrantTooLongException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SupportGrant: at most 24 h, ACTIVE → EXPIRED (by time) or ACTIVE → REVOKED (D83, r3-decisions §8.3). */
class SupportGrantTest {

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    static final UUID ACCOUNT = UUID.fromString("0190b000-0000-7000-8000-000000000001");
    static final UUID ADMIN = UUID.fromString("0190b000-0000-7000-8000-000000000002");

    @Test
    void aGrantOfExactly24HoursIsAllowedAndActiveUntilItsEnd() {
        SupportGrant grant = SupportGrant.grant(ACCOUNT, Duration.ofHours(24), "vendor ticket 42", ADMIN, NOW);

        assertThat(grant.getValidUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(grant.statusAt(NOW.plus(Duration.ofHours(24)).minusNanos(1000))).isEqualTo(SupportGrantStatus.ACTIVE);
        assertThat(grant.isUsableAt(NOW.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void aGrantLongerThan24HoursIsRefused() {
        assertThatThrownBy(() -> SupportGrant.grant(ACCOUNT, Duration.ofHours(24).plusMinutes(1), "too long", ADMIN,
                NOW)).isInstanceOf(SupportGrantTooLongException.class);
    }

    @Test
    void aGrantWithoutPositiveDurationIsRefused() {
        assertThatThrownBy(() -> SupportGrant.grant(ACCOUNT, Duration.ZERO, "nothing", ADMIN, NOW))
                .isInstanceOf(SupportGrantTooLongException.class);
    }

    @Test
    void anActiveGrantPastItsEndCountsAsExpiredBeforeTheJobRuns() {
        SupportGrant grant = SupportGrant.grant(ACCOUNT, Duration.ofHours(2), "ticket", ADMIN, NOW);

        assertThat(grant.statusAt(NOW.plus(Duration.ofHours(2)))).isEqualTo(SupportGrantStatus.EXPIRED);
    }

    @Test
    void revokingEndsTheGrantAndRecordsWho() {
        SupportGrant grant = SupportGrant.grant(ACCOUNT, Duration.ofHours(2), "ticket", ADMIN, NOW);

        grant.revoke(ADMIN, NOW.plusSeconds(60));

        assertThat(grant.statusAt(NOW.plusSeconds(61))).isEqualTo(SupportGrantStatus.REVOKED);
        assertThat(grant.isUsableAt(NOW.plusSeconds(61))).isFalse();
        assertThat(grant.getRevokedByAccountId()).isEqualTo(ADMIN);
    }

    @Test
    void anEndedGrantCannotBeRevokedAgain() {
        SupportGrant grant = SupportGrant.grant(ACCOUNT, Duration.ofHours(2), "ticket", ADMIN, NOW);
        grant.revoke(ADMIN, NOW.plusSeconds(60));

        assertThatThrownBy(() -> grant.revoke(ADMIN, NOW.plusSeconds(120)))
                .isInstanceOf(IllegalSupportGrantStateException.class);
    }
}
