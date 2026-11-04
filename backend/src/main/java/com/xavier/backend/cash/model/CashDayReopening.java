package com.xavier.backend.cash.model;

import com.xavier.backend.access.ActorKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A reopen of a cash day (P19): who asked, which approver authorized it, why, and the operational close it undid.
 * A fact: never updated or deleted (trigger in V8002).
 */
@Entity
@Table(schema = "cash", name = "cash_day_reopening")
public class CashDayReopening {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID cashDayId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private Instant reopenedAt;

    @Column(nullable = false, updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ActorKind actorKind;

    @Column(nullable = false, updatable = false)
    private UUID approverEmployeeId;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant previousClosedAt;

    @Column(nullable = false, updatable = false)
    private UUID previousClosedById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ActorKind previousClosedByKind;

    protected CashDayReopening() {
        // JPA
    }

    CashDayReopening(CashDay day, UUID actorId, ActorKind actorKind, UUID approverEmployeeId, String reason,
                     Instant now, Instant previousClosedAt, UUID previousClosedById, ActorKind previousClosedByKind) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reopen needs a reason");
        }
        this.cashDayId = day.getId();
        this.storeId = day.getStoreId();
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.actorKind = Objects.requireNonNull(actorKind, "actorKind");
        this.approverEmployeeId = Objects.requireNonNull(approverEmployeeId, "approverEmployeeId");
        this.reason = reason.strip();
        this.reopenedAt = now;
        this.previousClosedAt = previousClosedAt;
        this.previousClosedById = previousClosedById;
        this.previousClosedByKind = previousClosedByKind;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApproverEmployeeId() {
        return approverEmployeeId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getPreviousClosedAt() {
        return previousClosedAt;
    }
}
