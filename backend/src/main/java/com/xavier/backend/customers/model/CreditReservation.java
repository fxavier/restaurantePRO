package com.xavier.backend.customers.model;

import com.xavier.backend.customers.ReservationView;
import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Part of a credit limit held for one credit charge (D73) until its debit is posted or the charge is cancelled. One
 * per charge ({@code credit_reservation_charge_uk}); the database refuses any other change than closing an ACTIVE
 * reservation, and refuses DELETE (V11003). It never expires on its own (D74).
 */
@Entity
@Table(schema = "customers", name = "credit_reservation")
public class CreditReservation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private UUID chargeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    protected CreditReservation() {
        // JPA
    }

    public static CreditReservation hold(UUID customerId, Money amount, UUID chargeId, Instant now) {
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("A reservation amount must be positive");
        }
        CreditReservation reservation = new CreditReservation();
        reservation.customerId = Objects.requireNonNull(customerId, "customerId");
        reservation.amount = amount.amount();
        reservation.currency = amount.currency().getCurrencyCode();
        reservation.chargeId = Objects.requireNonNull(chargeId, "chargeId");
        reservation.status = ReservationStatus.ACTIVE;
        reservation.createdAt = now;
        return reservation;
    }

    /** ACTIVE → CONSUMED, when the charge's debit is posted. */
    public void consume(Instant now) {
        if (status != ReservationStatus.ACTIVE) {
            throw CustomersConflictException.reservationClosed(chargeId, status, "be consumed");
        }
        status = ReservationStatus.CONSUMED;
        closedAt = now;
    }

    /** ACTIVE → RELEASED; already RELEASED is a no-op. A CONSUMED reservation never comes back. */
    public void release(Instant now) {
        if (status == ReservationStatus.RELEASED) {
            return;
        }
        if (status != ReservationStatus.ACTIVE) {
            throw CustomersConflictException.reservationClosed(chargeId, status, "be released");
        }
        status = ReservationStatus.RELEASED;
        closedAt = now;
    }

    public ReservationView toView() {
        return new ReservationView(id, customerId, chargeId, amount(), status.name());
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getChargeId() {
        return chargeId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
