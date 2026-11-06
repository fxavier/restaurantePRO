package com.xavier.backend.customers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The §8.3 machine: ACTIVE → CONSUMED or ACTIVE → RELEASED, every other command refused. */
class CreditReservationTest {

    static final Currency MZN = Currency.getInstance("MZN");
    final Instant now = Instant.parse("2026-11-05T10:00:00Z");

    CreditReservation active() {
        return CreditReservation.hold(UUID.randomUUID(), Money.of("60.00", MZN), UUID.randomUUID(), now);
    }

    @Test
    void anActiveReservationIsConsumedByItsDebit() {
        CreditReservation reservation = active();

        reservation.consume(now.plusSeconds(1));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(reservation.getClosedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void anActiveReservationIsReleasedAndAReleaseAgainChangesNothing() {
        CreditReservation reservation = active();

        reservation.release(now.plusSeconds(1));
        reservation.release(now.plusSeconds(2));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.getClosedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void aConsumedReservationIsNeitherReleasedNorConsumedAgain() {
        CreditReservation reservation = active();
        reservation.consume(now);

        assertThatThrownBy(() -> reservation.release(now)).isInstanceOf(CustomersConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_ACTIVE");
        assertThatThrownBy(() -> reservation.consume(now)).isInstanceOf(CustomersConflictException.class);
    }

    @Test
    void aReleasedReservationIsNotConsumed() {
        CreditReservation reservation = active();
        reservation.release(now);

        assertThatThrownBy(() -> reservation.consume(now)).isInstanceOf(CustomersConflictException.class);
    }

    @Test
    void anAmountMustBePositive() {
        assertThatThrownBy(() -> CreditReservation.hold(UUID.randomUUID(), Money.of("0.00", MZN),
                UUID.randomUUID(), now)).isInstanceOf(IllegalArgumentException.class);
    }
}
