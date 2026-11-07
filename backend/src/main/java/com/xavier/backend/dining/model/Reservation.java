package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.IllegalReservationStateException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.exception.ReservationRuleException;
import com.xavier.backend.dining.model.dto.ReservationStatus;
import com.xavier.backend.dining.model.dto.ReservationView;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A booking of a party in a store (CONTEXT.md: Reserva, spec §4, §8.3; D126, P47). It is informative: it never
 * blocks a table open, a lock or a sale, and there is no capacity check. Only a BOOKED reservation changes; SEATED,
 * CANCELLED and NO_SHOW are final.
 */
@Entity
@Table(schema = "dining", name = "reservation")
public class Reservation {

    /** The actor kind of a job (the no-show sweep); {@code access.ActorKind} only names session actors. */
    public static final String SYSTEM = "SYSTEM";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false)
    private Instant startsAt;

    private Integer durationMinutes;

    @Column(nullable = false)
    private int partySize;

    @Column(nullable = false, length = 120)
    private String contactName;

    @Column(length = 20)
    private String contactPhoneDigits;

    private UUID customerId;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    private UUID occupationId;

    private Instant seatedAt;

    private Instant cancelledAt;

    @Column(length = 500)
    private String cancelReason;

    private Instant noShowAt;

    @Column(length = 20)
    private String noShowByActorKind;

    @Column(nullable = false, updatable = false, length = 20)
    private String createdByActorKind;

    @Column(updatable = false)
    private UUID createdByActorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "dining", name = "reservation_table", joinColumns = @JoinColumn(name = "reservation_id"))
    @Column(name = "table_id", nullable = false)
    private Set<UUID> tableIds = new HashSet<>();

    protected Reservation() {
        // JPA
    }

    public static Reservation book(UUID storeId, ReservationDetails details, String actorKind, UUID actorId,
                                   Instant now) {
        Reservation reservation = new Reservation();
        reservation.storeId = storeId;
        reservation.status = ReservationStatus.BOOKED;
        reservation.createdByActorKind = actorKind;
        reservation.createdByActorId = actorId;
        reservation.createdAt = now;
        reservation.apply(details, now);
        return reservation;
    }

    public void update(ReservationDetails details, Instant now) {
        requireBooked();
        apply(details, now);
    }

    /** Seats the party: the occupation opened for it (through the normal table open, D126) is linked. */
    public void seat(UUID occupationId, Instant now) {
        requireBooked();
        this.status = ReservationStatus.SEATED;
        this.occupationId = occupationId;
        this.seatedAt = now;
        this.updatedAt = now;
    }

    public void cancel(String reason, Instant now) {
        requireBooked();
        this.cancelReason = DiningValues.requireText(reason, "reason");
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    /** P47: staff once the start has passed, or the job ({@link #SYSTEM}) once the grace has passed too. */
    public void markNoShow(String actorKind, Instant now) {
        requireBooked();
        if (now.isBefore(startsAt)) {
            throw ReservationRuleException.notStarted(id);
        }
        this.status = ReservationStatus.NO_SHOW;
        this.noShowAt = now;
        this.noShowByActorKind = actorKind;
        this.updatedAt = now;
    }

    public ReservationView toView() {
        return new ReservationView(id, storeId, startsAt, durationMinutes, partySize, contactName,
                contactPhoneDigits, customerId, notes, status, tableIds.stream().sorted().toList(), occupationId,
                seatedAt, cancelledAt, cancelReason, noShowAt, noShowByActorKind, createdAt, version);
    }

    private void apply(ReservationDetails details, Instant now) {
        if (details.startsAt() == null) {
            throw new InvalidDiningSetupException("startsAt is required");
        }
        if (details.partySize() < 1) {
            throw new InvalidDiningSetupException("partySize must be at least 1");
        }
        if (details.durationMinutes() != null && details.durationMinutes() < 1) {
            throw new InvalidDiningSetupException("durationMinutes must be positive");
        }
        this.startsAt = details.startsAt();
        this.durationMinutes = details.durationMinutes();
        this.partySize = details.partySize();
        this.contactName = DiningValues.requireText(details.contactName(), "contactName");
        this.contactPhoneDigits = details.contactPhoneDigits();
        this.customerId = details.customerId();
        this.notes = details.notes() == null || details.notes().isBlank() ? null : details.notes().strip();
        this.tableIds.clear();
        this.tableIds.addAll(details.tableIds());
        this.updatedAt = now;
    }

    private void requireBooked() {
        if (status != ReservationStatus.BOOKED) {
            throw IllegalReservationStateException.notBooked(id);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getPartySize() {
        return partySize;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public UUID getOccupationId() {
        return occupationId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getNoShowByActorKind() {
        return noShowByActorKind;
    }

    public Set<UUID> getTableIds() {
        return Set.copyOf(tableIds);
    }

    public Long getVersion() {
        return version;
    }
}
