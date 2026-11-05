package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.exception.PrintJobConflictException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * PrintJob (r2-decisions §4, D50 §7.2): QUEUED → PROCESSING → SENT | RETRY_WAIT | FAILED | UNKNOWN, RETRY_WAIT →
 * PROCESSING; backoff 2, 4, 8, 16 s and FAILED after the fifth refused attempt; an UNKNOWN job is acknowledged or
 * retried, never resent on its own. Every state × command.
 */
class PrintJobTest {

    static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");
    static final UUID TICKET = UUID.randomUUID();
    static final UUID STORE = UUID.randomUUID();
    static final UUID PRINTER = UUID.randomUUID();

    @Test
    void aNewJobIsQueuedForNowAndKeyedByItsTicket() {
        PrintJob job = PrintJob.first(TICKET, STORE, PRINTER, JobKind.ORIGINAL, T0);

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.QUEUED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getNextAttemptAt()).isEqualTo(T0);
        assertThat(job.getDedupeKey()).isEqualTo("ticket:" + TICKET);
    }

    @Test
    void claimingStartsAnAttempt() {
        PrintJob job = queued();

        job.claim(T0.plusSeconds(1));

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.PROCESSING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getStartedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void anAcceptedSendIsSent() {
        PrintJob job = processing();

        job.recordSent(T0.plusSeconds(2));

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.SENT);
        assertThat(job.getSentAt()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    void aRefusedConnectionWaitsTwoFourEightSixteenSecondsThenFailsAfterTheFifthAttempt() {
        PrintJob job = queued();
        Instant now = T0;
        for (long wait : new long[] {2, 4, 8, 16}) {
            job.claim(now);
            job.recordNotConnected(now);
            assertThat(job.getStatus()).isEqualTo(PrintJobStatus.RETRY_WAIT);
            assertThat(job.getNextAttemptAt()).isEqualTo(now.plusSeconds(wait));
            assertThat(job.getLastError()).isEqualTo("NOT_CONNECTED");
            now = job.getNextAttemptAt();
        }

        job.claim(now);
        job.recordNotConnected(now);

        assertThat(job.getAttempts()).isEqualTo(PrintJob.MAX_ATTEMPTS).isEqualTo(5);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.FAILED);
    }

    @Test
    void aSendInterruptedAfterWritingIsUnknown() {
        PrintJob job = processing();

        job.recordInterrupted();

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.UNKNOWN);
        assertThat(job.getLastError()).isEqualTo("INTERRUPTED_AFTER_WRITE");
    }

    @Test
    void anExpiredLeaseIsUnknownNeverResent() {
        PrintJob job = processing();

        assertThat(job.leaseExpired(T0.plus(PrintJob.LEASE), PrintJob.LEASE)).isFalse();
        assertThat(job.leaseExpired(T0.plus(PrintJob.LEASE).plusMillis(1), PrintJob.LEASE)).isTrue();
        job.expireLease();

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.UNKNOWN);
        assertThat(job.getLastError()).isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void anUnknownJobCanBeAcknowledgedAsPrintedOnce() {
        PrintJob job = unknown();

        job.acknowledgePrinted();

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.UNKNOWN);
        assertThat(job.getResolution()).isEqualTo(PrintResolution.CONFIRMED_PRINTED);
        assertThatThrownBy(job::acknowledgePrinted).isInstanceOf(PrintJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "PRINT_JOB_ALREADY_RESOLVED");
        assertThatThrownBy(() -> job.retry(PRINTER, T0)).isInstanceOf(PrintJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "PRINT_JOB_ALREADY_RESOLVED");
    }

    @ParameterizedTest
    @EnumSource(value = PrintJobStatus.class, names = {"FAILED", "UNKNOWN"})
    void aRetryIsANewJobOfTheSameTicketAndKindAndResolvesTheOldOne(PrintJobStatus status) {
        PrintJob job = in(status);
        UUID other = UUID.randomUUID();

        PrintJob retry = job.retry(other, T0.plusSeconds(90));

        assertThat(job.getResolution()).isEqualTo(PrintResolution.RETRIED);
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(retry.getStatus()).isEqualTo(PrintJobStatus.QUEUED);
        assertThat(retry.getRetryOf()).isEqualTo(job.getId());
        assertThat(retry.getDedupeKey()).isEqualTo("retry:" + job.getId());
        assertThat(retry.getTicketId()).isEqualTo(TICKET);
        assertThat(retry.getPrinterId()).isEqualTo(other);
        assertThat(retry.getKind()).isEqualTo(job.getKind());
        assertThatThrownBy(() -> job.retry(PRINTER, T0)).isInstanceOf(PrintJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "PRINT_JOB_ALREADY_RESOLVED");
    }

    @Test
    void aCopyIsKeyedByItsTicketAndNumber() {
        PrintJob copy = PrintJob.copy(TICKET, STORE, PRINTER, 2, T0);

        assertThat(copy.getKind()).isEqualTo(JobKind.COPY);
        assertThat(copy.getDedupeKey()).isEqualTo("copy:" + TICKET + ":2");
        assertThat(copy.getStatus()).isEqualTo(PrintJobStatus.QUEUED);
    }

    @ParameterizedTest
    @EnumSource(value = PrintJobStatus.class, names = {"QUEUED", "RETRY_WAIT", "FAILED"})
    void pendingAndFailedJobsMoveToARedirectTarget(PrintJobStatus status) {
        PrintJob job = in(status);
        UUID target = UUID.randomUUID();

        job.moveTo(target);

        assertThat(job.getPrinterId()).isEqualTo(target);
    }

    /** Every command refused in every state it is not defined for: a 409 with a stable code. */
    @Test
    void everyIllegalTransitionIsAConflict() {
        Map<String, Command> commands = Map.of(
                "claim", new Command(EnumSet.of(PrintJobStatus.QUEUED, PrintJobStatus.RETRY_WAIT),
                        job -> job.claim(T0)),
                "sent", new Command(EnumSet.of(PrintJobStatus.PROCESSING), job -> job.recordSent(T0)),
                "notConnected", new Command(EnumSet.of(PrintJobStatus.PROCESSING),
                        job -> job.recordNotConnected(T0)),
                "interrupted", new Command(EnumSet.of(PrintJobStatus.PROCESSING), job -> job.recordInterrupted()),
                "lease", new Command(EnumSet.of(PrintJobStatus.PROCESSING), job -> job.expireLease()),
                "acknowledge", new Command(EnumSet.of(PrintJobStatus.UNKNOWN), PrintJob::acknowledgePrinted),
                "retry", new Command(EnumSet.of(PrintJobStatus.FAILED, PrintJobStatus.UNKNOWN),
                        job -> job.retry(PRINTER, T0)),
                "move", new Command(EnumSet.of(PrintJobStatus.QUEUED, PrintJobStatus.RETRY_WAIT,
                        PrintJobStatus.FAILED), job -> job.moveTo(PRINTER)));
        commands.forEach((name, command) -> {
            for (PrintJobStatus status : PrintJobStatus.values()) {
                PrintJob job = in(status);
                if (command.allowed().contains(status)) {
                    command.action().accept(job);
                } else {
                    assertThatThrownBy(() -> command.action().accept(job)).as(name + " in " + status)
                            .isInstanceOf(PrintJobConflictException.class)
                            .hasFieldOrPropertyWithValue("code", "ILLEGAL_PRINT_JOB_STATE");
                }
            }
        });
    }

    record Command(EnumSet<PrintJobStatus> allowed, Consumer<PrintJob> action) {
    }

    static PrintJob in(PrintJobStatus status) {
        return switch (status) {
            case QUEUED -> queued();
            case PROCESSING -> processing();
            case SENT -> {
                PrintJob job = processing();
                job.recordSent(T0);
                yield job;
            }
            case RETRY_WAIT -> {
                PrintJob job = processing();
                job.recordNotConnected(T0);
                yield job;
            }
            case FAILED -> {
                PrintJob job = queued();
                for (int i = 0; i < PrintJob.MAX_ATTEMPTS; i++) {
                    job.claim(T0);
                    job.recordNotConnected(T0);
                }
                yield job;
            }
            case UNKNOWN -> unknown();
        };
    }

    static PrintJob queued() {
        return withId(PrintJob.first(TICKET, STORE, PRINTER, JobKind.ORIGINAL, T0));
    }

    static PrintJob processing() {
        PrintJob job = queued();
        job.claim(T0);
        return job;
    }

    static PrintJob unknown() {
        PrintJob job = processing();
        job.recordInterrupted();
        return job;
    }

    /** The id Hibernate assigns on persist (UUIDv7). */
    static PrintJob withId(PrintJob job) {
        try {
            Field id = PrintJob.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(job, UUID.randomUUID());
            return job;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
