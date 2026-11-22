package com.xavier.backend.production.model;

import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.production.model.dto.KitchenProgressView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A round's progress on the kitchen display (ADR 0044), one per BATCH ticket, created with it. The ticket stays an
 * append-only fact; this is the operational state the kitchen moves along (spec §8.3 "preparação e impressão têm
 * estados próprios"). Each step keeps its time and employee; a recall clears the steps it undid. Every step is also
 * an audit row, which is the history.
 */
@Entity
@Table(schema = "production", name = "kitchen_progress")
public class KitchenProgress {

    @Id
    private UUID ticketId;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 40)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KitchenStatus status;

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant queuedAt;

    private Instant startedAt;
    private UUID startedBy;
    private Instant readyAt;
    private UUID readyBy;
    private Instant bumpedAt;
    private UUID bumpedBy;
    private Instant recalledAt;
    private UUID recalledBy;

    protected KitchenProgress() {
        // JPA
    }

    /** A round just received on its production destination (the KDS station). */
    public static KitchenProgress queue(UUID ticketId, UUID storeId, String destination, Instant now) {
        KitchenProgress progress = new KitchenProgress();
        progress.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        progress.storeId = Objects.requireNonNull(storeId, "storeId");
        progress.destination = Objects.requireNonNull(destination, "destination");
        progress.status = KitchenStatus.QUEUED;
        progress.queuedAt = Objects.requireNonNull(now, "now");
        return progress;
    }

    public void start(UUID employee, Instant now) {
        require(KitchenStatus.QUEUED, "start");
        status = KitchenStatus.IN_PREPARATION;
        startedAt = now;
        startedBy = employee;
    }

    public void ready(UUID employee, Instant now) {
        require(KitchenStatus.IN_PREPARATION, "ready");
        status = KitchenStatus.READY;
        readyAt = now;
        readyBy = employee;
    }

    public void bump(UUID employee, Instant now) {
        require(KitchenStatus.READY, "bump");
        status = KitchenStatus.BUMPED;
        bumpedAt = now;
        bumpedBy = employee;
    }

    /** One step back: a READY round goes back to preparation, a BUMPED one back on the screen as READY. */
    public void recall(UUID employee, Instant now) {
        switch (status) {
            case READY -> {
                status = KitchenStatus.IN_PREPARATION;
                readyAt = null;
                readyBy = null;
            }
            case BUMPED -> {
                status = KitchenStatus.READY;
                bumpedAt = null;
                bumpedBy = null;
            }
            default -> throw ProductionConflictException.illegalKitchenState(ticketId, status, "recall");
        }
        recalledAt = now;
        recalledBy = employee;
    }

    private void require(KitchenStatus expected, String command) {
        if (status != expected) {
            throw ProductionConflictException.illegalKitchenState(ticketId, status, command);
        }
    }

    public KitchenProgressView toView() {
        return new KitchenProgressView(status, version == null ? 0 : version, queuedAt, startedAt, startedBy,
                readyAt, readyBy, bumpedAt, bumpedBy, recalledAt, recalledBy);
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getDestination() {
        return destination;
    }

    public KitchenStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
