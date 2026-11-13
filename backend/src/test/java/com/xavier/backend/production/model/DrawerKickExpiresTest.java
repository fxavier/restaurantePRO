package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.exception.PrintJobConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D158: a drawer kick claimed more than 30 s after its creation is late, a risk (a drawer opening unattended), so it
 * is FAILED unsent; a kick is never retried: a send that wrote nothing fails it at once.
 */
class DrawerKickExpiresTest {

    static final Clock CREATED = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void aKickIsDueUpToThirtySecondsAfterItsCreation() {
        PrintJob kick = kick();

        assertThat(kick.kickExpired(CREATED.instant().plus(PrintJob.KICK_TTL))).isFalse();
        assertThat(kick.kickExpired(CREATED.instant().plus(PrintJob.KICK_TTL).plusMillis(1))).isTrue();
        assertThat(PrintJob.KICK_TTL).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aQueuedKickFailsUnsentWithItsCause() {
        PrintJob kick = kick();
        Instant decided = CREATED.instant().plusSeconds(31);

        kick.failUnsent(decided, "KICK_EXPIRED");

        assertThat(kick.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(kick.getLastError()).isEqualTo("KICK_EXPIRED");
        assertThat(kick.getAttempts()).isZero();
        assertThat(kick.getStartedAt()).as("print_job_started_ck: when the queue decided").isEqualTo(decided);
    }

    @Test
    void aKickWhoseSendWroteNothingFailsAtItsFirstAttempt() {
        PrintJob kick = kick();
        kick.claim(CREATED.instant());

        kick.failUnsent(CREATED.instant().plusSeconds(2), "NOT_CONNECTED");

        assertThat(kick.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(kick.getLastError()).isEqualTo("NOT_CONNECTED");
        assertThat(kick.getAttempts()).isEqualTo(1);
        assertThat(kick.getStartedAt()).as("its attempt's start").isEqualTo(CREATED.instant());
    }

    @Test
    void aSentKickCannotFail() {
        PrintJob kick = kick();
        kick.claim(CREATED.instant());
        kick.recordSent(CREATED.instant());

        assertThatThrownBy(() -> kick.failUnsent(CREATED.instant(), "KICK_EXPIRED"))
                .isInstanceOf(PrintJobConflictException.class);
    }

    private static PrintJob kick() {
        return PrintJob.first(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), JobKind.ORIGINAL,
                CREATED.instant());
    }
}
