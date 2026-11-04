package com.xavier.backend.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.exception.IllegalTerminalStateException;
import com.xavier.backend.organization.exception.InvalidEnrollmentCodeException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final Instant EXPIRES = NOW.plus(Duration.ofHours(24));
    static final String CODE_HASH = "c".repeat(64);
    static final String TOKEN_HASH = "t".repeat(64);

    private final Terminal terminal = Terminal.register(UUID.randomUUID(), "POS1", "Bar", NOW);

    @Test
    void aRegisteredTerminalEnrollsWithItsCodeAndBecomesActive() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);

        terminal.enroll(CODE_HASH, TOKEN_HASH, NOW.plusSeconds(60));

        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.ACTIVE);
        assertThat(terminal.getDeviceTokenHash()).isEqualTo(TOKEN_HASH);
    }

    @Test
    void anEnrollmentCodeWorksOnlyOnce() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);
        terminal.enroll(CODE_HASH, TOKEN_HASH, NOW.plusSeconds(60));

        assertThatThrownBy(() -> terminal.enroll(CODE_HASH, "u".repeat(64), NOW.plusSeconds(120)))
                .isInstanceOf(InvalidEnrollmentCodeException.class);
        assertThat(terminal.getDeviceTokenHash()).isEqualTo(TOKEN_HASH);
    }

    @Test
    void anEnrollmentCodeExpires() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);

        assertThatThrownBy(() -> terminal.enroll(CODE_HASH, TOKEN_HASH, EXPIRES))
                .isInstanceOf(InvalidEnrollmentCodeException.class);
        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.REGISTERED);
    }

    @Test
    void aWrongCodeDoesNotEnroll() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);

        assertThatThrownBy(() -> terminal.enroll("x".repeat(64), TOKEN_HASH, NOW))
                .isInstanceOf(InvalidEnrollmentCodeException.class);
    }

    @Test
    void aNewCodeReplacesThePreviousOne() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);
        terminal.issueEnrollmentCode("d".repeat(64), EXPIRES, NOW);

        assertThatThrownBy(() -> terminal.enroll(CODE_HASH, TOKEN_HASH, NOW))
                .isInstanceOf(InvalidEnrollmentCodeException.class);
    }

    @Test
    void anEnrolledTerminalGetsNoNewCode() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);
        terminal.enroll(CODE_HASH, TOKEN_HASH, NOW);

        assertThatThrownBy(() -> terminal.issueEnrollmentCode("d".repeat(64), EXPIRES, NOW))
                .isInstanceOf(IllegalTerminalStateException.class);
    }

    @Test
    void aRevokedTerminalCanNeitherEnrollNorBeRevokedAgain() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);
        terminal.revoke(NOW);

        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.REVOKED);
        assertThatThrownBy(() -> terminal.enroll(CODE_HASH, TOKEN_HASH, NOW))
                .isInstanceOf(InvalidEnrollmentCodeException.class);
        assertThatThrownBy(() -> terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW))
                .isInstanceOf(IllegalTerminalStateException.class);
        assertThatThrownBy(() -> terminal.revoke(NOW)).isInstanceOf(IllegalTerminalStateException.class);
    }

    @Test
    void anActiveTerminalCanBeRevoked() {
        terminal.issueEnrollmentCode(CODE_HASH, EXPIRES, NOW);
        terminal.enroll(CODE_HASH, TOKEN_HASH, NOW);

        terminal.revoke(NOW);

        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.REVOKED);
    }
}
