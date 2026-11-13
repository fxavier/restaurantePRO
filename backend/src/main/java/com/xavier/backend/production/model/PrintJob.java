package com.xavier.backend.production.model;

import com.xavier.backend.production.exception.PrintJobConflictException;
import com.xavier.backend.production.model.dto.PrintJobView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One physical delivery attempt of a production ticket to a printer (spec §8.1, r2-decisions §4, D50 §7.2). The
 * worker claims it (PROCESSING), sends with no transaction, and records the outcome. A refused connection waits
 * 2, 4, 8 and 16 s and FAILED follows the fifth refused attempt; an interrupted send, or a PROCESSING job whose
 * lease expired (a crash during the send), is UNKNOWN and is never resent on its own. A manual retry is a new job
 * ({@code retryOf}); {@code dedupeKey} (unique) makes every job creation idempotent.
 */
@Entity
@Table(schema = "production", name = "print_job")
public class PrintJob {

    /** Refused attempts before FAILED. */
    public static final int MAX_ATTEMPTS = 5;
    /** A PROCESSING job older than this was lost in a crash during its send: UNKNOWN. */
    public static final Duration LEASE = Duration.ofSeconds(60);

    /** D158: a drawer kick claimed later than this after its creation is not sent (a late drawer is a risk). */
    public static final Duration KICK_TTL = Duration.ofSeconds(30);

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID ticketId;

    @Column(nullable = false)
    private UUID printerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private JobKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PrintJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant startedAt;

    private Instant sentAt;

    @Column(length = 40)
    private String lastError;

    @Column(nullable = false, updatable = false, length = 120)
    private String dedupeKey;

    @Column(updatable = false)
    private UUID retryOf;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PrintResolution resolution;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected PrintJob() {
        // JPA
    }

    /** The first job of a ticket. */
    public static PrintJob first(UUID ticketId, UUID storeId, UUID printerId, JobKind kind, Instant now) {
        return new PrintJob(ticketId, storeId, printerId, kind, "ticket:" + ticketId, null, now);
    }

    /** The {@code copyNo}-th reprint of a ticket (spec §4: identified as a copy). */
    public static PrintJob copy(UUID ticketId, UUID storeId, UUID printerId, int copyNo, Instant now) {
        return new PrintJob(ticketId, storeId, printerId, JobKind.COPY, "copy:" + ticketId + ":" + copyNo, null, now);
    }

    private PrintJob(UUID ticketId, UUID storeId, UUID printerId, JobKind kind, String dedupeKey, UUID retryOf,
                     Instant now) {
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.printerId = Objects.requireNonNull(printerId, "printerId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.dedupeKey = dedupeKey;
        this.retryOf = retryOf;
        this.status = PrintJobStatus.QUEUED;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    /** QUEUED or RETRY_WAIT → PROCESSING: one more attempt starts now. */
    public void claim(Instant now) {
        require("be claimed", PrintJobStatus.QUEUED, PrintJobStatus.RETRY_WAIT);
        status = PrintJobStatus.PROCESSING;
        attempts++;
        startedAt = now;
    }

    /** The printer took the bytes and the connection closed cleanly. */
    public void recordSent(Instant now) {
        require("be sent", PrintJobStatus.PROCESSING);
        status = PrintJobStatus.SENT;
        sentAt = now;
        lastError = null;
    }

    /**
     * Nothing was written ({@code cause}: NOT_CONNECTED, or what the status probe found, D157): wait 2^attempts
     * seconds, or FAILED after {@link #MAX_ATTEMPTS}.
     */
    public void recordNothingWritten(Instant now, String cause) {
        require("record a send that wrote nothing", PrintJobStatus.PROCESSING);
        lastError = Objects.requireNonNull(cause, "cause");
        if (attempts >= MAX_ATTEMPTS) {
            status = PrintJobStatus.FAILED;
            return;
        }
        status = PrintJobStatus.RETRY_WAIT;
        nextAttemptAt = now.plus(FIRST_BACKOFF.multipliedBy(1L << (attempts - 1)));
    }

    /** D158: as a drawer kick, too late to send at {@code now}. */
    public boolean kickExpired(Instant now) {
        return createdAt.plus(KICK_TTL).isBefore(now);
    }

    /**
     * D158: a drawer kick gets one attempt and is never held: FAILED with {@code cause} instead of waiting (the
     * printer not NORMAL, the kick expired) or retrying (its send wrote nothing). One never attempted is stamped
     * as started when the queue decided ({@code print_job_started_ck}).
     */
    public void failUnsent(Instant now, String cause) {
        require("fail unsent", PrintJobStatus.QUEUED, PrintJobStatus.PROCESSING);
        status = PrintJobStatus.FAILED;
        if (startedAt == null) {
            startedAt = now;
        }
        lastError = Objects.requireNonNull(cause, "cause");
    }

    /** Bytes may have reached the printer: the outcome is unknown and needs a person (spec §4). */
    public void recordInterrupted() {
        require("record an interrupted send", PrintJobStatus.PROCESSING);
        status = PrintJobStatus.UNKNOWN;
        lastError = "INTERRUPTED_AFTER_WRITE";
    }

    public boolean leaseExpired(Instant now, Duration lease) {
        return status == PrintJobStatus.PROCESSING && startedAt.plus(lease).isBefore(now);
    }

    /** A crash during the send left the job PROCESSING: UNKNOWN, never resent on its own. */
    public void expireLease() {
        require("expire its lease", PrintJobStatus.PROCESSING);
        status = PrintJobStatus.UNKNOWN;
        lastError = "LEASE_EXPIRED";
    }

    /** Someone saw the ticket come out: UNKNOWN stays the status, the resolution says it printed. */
    public void acknowledgePrinted() {
        require("be acknowledged", PrintJobStatus.UNKNOWN);
        requireUnresolved();
        resolution = PrintResolution.CONFIRMED_PRINTED;
    }

    /** FAILED or UNKNOWN: a new job of the same ticket on {@code printerId}; this one is resolved as RETRIED. */
    public PrintJob retry(UUID targetPrinterId, Instant now) {
        require("be retried", PrintJobStatus.FAILED, PrintJobStatus.UNKNOWN);
        requireUnresolved();
        resolution = PrintResolution.RETRIED;
        return new PrintJob(ticketId, storeId, targetPrinterId, kind, "retry:" + Objects.requireNonNull(id, "id"),
                id, now);
    }

    /** REDIRECT (D56): a job not yet delivered goes to the redirect target. */
    public void moveTo(UUID targetPrinterId) {
        require("move", PrintJobStatus.QUEUED, PrintJobStatus.RETRY_WAIT, PrintJobStatus.FAILED);
        printerId = Objects.requireNonNull(targetPrinterId, "targetPrinterId");
    }

    public PrintJobView toView(ProductionTicket ticket) {
        return new PrintJobView(id, storeId, ticketId, printerId, kind, status, attempts, nextAttemptAt, lastError,
                retryOf, resolution, createdAt, sentAt, ticket.getSourceType(), ticket.getSourceId(),
                ticket.getDestination(), ticket.getOrderId());
    }

    private void require(String command, PrintJobStatus first, PrintJobStatus... others) {
        if (!EnumSet.of(first, others).contains(status)) {
            throw PrintJobConflictException.illegalState(id, status, command);
        }
    }

    private void requireUnresolved() {
        if (resolution != null) {
            throw PrintJobConflictException.alreadyResolved(id);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public JobKind getKind() {
        return kind;
    }

    public PrintJobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public UUID getRetryOf() {
        return retryOf;
    }

    public PrintResolution getResolution() {
        return resolution;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
