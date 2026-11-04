package com.xavier.backend.cash.model;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The cash side of a store's business day (CONTEXT.md BusinessDay; spec §8.3): OPEN → OPERATIONALLY_CLOSED →
 * FINANCIALLY_CLOSED. One per store and business date ({@code cash_day_store_date_uk}); the service creates it when
 * the first session of that date opens, with {@code INSERT … ON CONFLICT DO NOTHING}, so two first sessions of the
 * day never collide. A reopen (P19) is a {@link CashDayReopening} fact that keeps the close it undid.
 */
@Entity
@Table(schema = "cash", name = "cash_day")
public class CashDay {

    static final String ENTITY = "CashDay";

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CashDayStatus status;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    private Instant operationallyClosedAt;

    private UUID operationallyClosedById;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ActorKind operationallyClosedByKind;

    private Instant financiallyClosedAt;

    private UUID financiallyClosedById;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ActorKind financiallyClosedByKind;

    protected CashDay() {
        // JPA
    }

    /** Sessions open, and money moves, only on an OPEN day. */
    public void requireOpen() {
        if (status != CashDayStatus.OPEN) {
            throw new CashRuleViolationException(CashRule.DAY_NOT_OPEN,
                    "The cash day " + businessDate + " is " + status + ".");
        }
    }

    /** No closing is created on a day whose money is already signed off. */
    public void requireNotFinanciallyClosed() {
        if (status == CashDayStatus.FINANCIALLY_CLOSED) {
            throw new CashRuleViolationException(CashRule.DAY_FINANCIALLY_CLOSED,
                    "The cash day " + businessDate + " is financially closed.");
        }
    }

    /** The caller has checked that every session of the day is CLOSED (and, in settlement, orders and attempts). */
    public void closeOperationally(UUID actorId, ActorKind actorKind, Instant now) {
        requireStatus(CashDayStatus.OPEN, "close operationally");
        status = CashDayStatus.OPERATIONALLY_CLOSED;
        operationallyClosedAt = now;
        operationallyClosedById = Objects.requireNonNull(actorId, "actorId");
        operationallyClosedByKind = Objects.requireNonNull(actorKind, "actorKind");
    }

    /** The caller has checked that every closing of the day is APPROVED. */
    public void closeFinancially(UUID actorId, ActorKind actorKind, Instant now) {
        requireStatus(CashDayStatus.OPERATIONALLY_CLOSED, "close financially");
        status = CashDayStatus.FINANCIALLY_CLOSED;
        financiallyClosedAt = now;
        financiallyClosedById = Objects.requireNonNull(actorId, "actorId");
        financiallyClosedByKind = Objects.requireNonNull(actorKind, "actorKind");
    }

    /** OPERATIONALLY_CLOSED → OPEN (P19). The returned fact keeps the close being undone. */
    public CashDayReopening reopen(UUID actorId, ActorKind actorKind, UUID approverEmployeeId, String reason,
                                   Instant now) {
        requireStatus(CashDayStatus.OPERATIONALLY_CLOSED, "reopen");
        CashDayReopening reopening = new CashDayReopening(this, actorId, actorKind, approverEmployeeId, reason, now,
                operationallyClosedAt, operationallyClosedById, operationallyClosedByKind);
        status = CashDayStatus.OPEN;
        operationallyClosedAt = null;
        operationallyClosedById = null;
        operationallyClosedByKind = null;
        return reopening;
    }

    public DayView toView() {
        return new DayView(id, storeId, businessDate, status.name(), versionOrZero());
    }

    private void requireStatus(CashDayStatus required, String command) {
        if (status != required) {
            throw new IllegalCashStateException(ENTITY, id, status, command);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public CashDayStatus getStatus() {
        return status;
    }

    /** Spring Data reads this to tell a new entity (null) from a stored one: never map null to 0 here. */
    public Long getVersion() {
        return version;
    }

    private long versionOrZero() {
        return version == null ? 0 : version;
    }
}
