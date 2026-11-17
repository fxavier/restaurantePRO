package com.xavier.backend.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.exception.IllegalTerminalStateException;
import com.xavier.backend.organization.model.Terminal.Presentation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D182: the one-time code of a store device, its failures, the key it pins, and revocation. */
class TerminalTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final Instant EXPIRES = NOW.plus(Duration.ofHours(24));
    static final String SECRET = "c".repeat(64);
    static final String WRONG = "x".repeat(64);
    static final String KEY = "k".repeat(43);
    static final String OTHER_KEY = "o".repeat(43);
    static final UUID PENDING = UUID.randomUUID();
    static final UUID MEMBERSHIP = UUID.randomUUID();

    private final Terminal terminal = Terminal.register(UUID.randomUUID(), DeviceKind.POS, "POS1", "Bar", NOW);

    @Test
    void theRightSecretPinsTheKeyAndTheEnrolmentConsumesTheCode() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);

        assertThat(terminal.presentCode(SECRET, KEY, NOW)).isEqualTo(Presentation.ACCEPTED);
        assertThat(terminal.completeEnrolment(KEY, MEMBERSHIP, NOW)).contains(PENDING);

        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.ACTIVE);
        assertThat(terminal.wasEnrolled()).isTrue();
        assertThat(terminal.presentCode(SECRET, KEY, NOW)).isEqualTo(Presentation.REFUSED);
        assertThat(terminal.pendingMembership()).isEmpty();
    }

    @Test
    void anotherKeyCannotFinishAnEnrolmentTheFirstKeyStarted() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);
        terminal.presentCode(SECRET, KEY, NOW);

        assertThat(terminal.presentCode(SECRET, OTHER_KEY, NOW)).isEqualTo(Presentation.REFUSED);
        assertThat(terminal.completeEnrolment(OTHER_KEY, MEMBERSHIP, NOW)).isEmpty();
        assertThat(terminal.presentCode(SECRET, KEY, NOW)).as("a retry with the first key")
                .isEqualTo(Presentation.ACCEPTED);
    }

    @Test
    void theFifthWrongSecretBurnsTheCode() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);
        for (int failure = 1; failure < Terminal.MAX_FAILURES; failure++) {
            assertThat(terminal.presentCode(WRONG, KEY, NOW)).isEqualTo(Presentation.REFUSED);
        }

        assertThat(terminal.presentCode(WRONG, KEY, NOW)).isEqualTo(Presentation.BURNED);
        assertThat(terminal.presentCode(SECRET, KEY, NOW)).isEqualTo(Presentation.REFUSED);
        assertThat(terminal.pendingMembership()).isEmpty();
    }

    @Test
    void aNewCodeStartsTheFailureCountAgainAndReplacesTheOldOne() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);
        for (int failure = 1; failure < Terminal.MAX_FAILURES; failure++) {
            terminal.presentCode(WRONG, KEY, NOW);
        }

        assertThat(terminal.issueEnrollmentCode("d".repeat(64), EXPIRES, UUID.randomUUID(), NOW)).contains(PENDING);
        assertThat(terminal.presentCode(WRONG, KEY, NOW)).isEqualTo(Presentation.REFUSED);
        assertThat(terminal.presentCode(SECRET, KEY, NOW)).as("the replaced code").isEqualTo(Presentation.REFUSED);
        assertThat(terminal.presentCode("d".repeat(64), KEY, NOW)).isEqualTo(Presentation.ACCEPTED);
    }

    @Test
    void aCodeExpires() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);

        assertThat(terminal.presentCode(SECRET, KEY, EXPIRES)).isEqualTo(Presentation.REFUSED);
        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.REGISTERED);
    }

    @Test
    void anEnrolledDeviceCanBeEnrolledAgainWithANewCode() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);
        terminal.presentCode(SECRET, KEY, NOW);
        terminal.completeEnrolment(KEY, MEMBERSHIP, NOW);

        terminal.issueEnrollmentCode("d".repeat(64), EXPIRES, UUID.randomUUID(), NOW);

        assertThat(terminal.presentCode("d".repeat(64), OTHER_KEY, NOW)).isEqualTo(Presentation.ACCEPTED);
        assertThat(terminal.completeEnrolment(OTHER_KEY, MEMBERSHIP, NOW)).isPresent();
    }

    @Test
    void revocationNamesEveryMembershipAndIsFinal() {
        terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW);
        terminal.presentCode(SECRET, KEY, NOW);
        terminal.completeEnrolment(KEY, MEMBERSHIP, NOW);
        UUID reissued = UUID.randomUUID();
        terminal.issueEnrollmentCode("d".repeat(64), EXPIRES, reissued, NOW);

        assertThat(terminal.revoke(NOW)).containsExactlyInAnyOrder(MEMBERSHIP, reissued);

        assertThat(terminal.getStatus()).isEqualTo(TerminalStatus.REVOKED);
        assertThat(terminal.presentCode("d".repeat(64), KEY, NOW)).isEqualTo(Presentation.REFUSED);
        assertThatThrownBy(() -> terminal.issueEnrollmentCode(SECRET, EXPIRES, PENDING, NOW))
                .isInstanceOf(IllegalTerminalStateException.class);
        assertThatThrownBy(() -> terminal.revoke(NOW)).isInstanceOf(IllegalTerminalStateException.class);
    }

    @Test
    void aDeviceIsItsKindsClient() {
        Terminal bridge = Terminal.register(UUID.randomUUID(), DeviceKind.PRINT_BRIDGE, "PB1", "Bridge", NOW);

        assertThat(bridge.clientId()).startsWith("bridge-");
        assertThat(terminal.clientId()).startsWith("pos-");
        assertThat(DeviceKind.PRINT_BRIDGE.membership().name()).isEqualTo("BRIDGE");
        assertThat(DeviceKind.POS.membership().name()).isEqualTo("TERMINAL");
    }
}
