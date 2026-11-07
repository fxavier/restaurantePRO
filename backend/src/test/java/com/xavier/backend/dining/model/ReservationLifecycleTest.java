package com.xavier.backend.dining.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.model.dto.ReservationStatus;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The §8.3 reservation machine (D126, P47): every state × every command. Only BOOKED moves. */
class ReservationLifecycleTest {

    static final Instant STARTS = Instant.parse("2026-11-20T19:00:00Z");
    static final Instant BEFORE_START = STARTS.minus(Duration.ofMinutes(10));
    static final Instant AFTER_START = STARTS.plus(Duration.ofMinutes(5));
    static final UUID STORE = UUID.fromString("0190f000-0000-7000-8000-00000000a001");
    static final UUID TABLE = UUID.fromString("0190f000-0000-7000-8000-00000000a002");
    static final UUID OCCUPATION = UUID.fromString("0190f000-0000-7000-8000-00000000a003");

    static ReservationDetails details(Instant startsAt) {
        return new ReservationDetails(startsAt, 90, 4, "Ana", "841234567", null, "window seat", Set.of(TABLE));
    }

    static Reservation booked() {
        return Reservation.book(STORE, details(STARTS), "EMPLOYEE", UUID.randomUUID(), BEFORE_START);
    }

    static Reservation in(ReservationStatus status) {
        Reservation reservation = booked();
        switch (status) {
            case BOOKED -> { }
            case SEATED -> reservation.seat(OCCUPATION, AFTER_START);
            case CANCELLED -> reservation.cancel("guest called", BEFORE_START);
            case NO_SHOW -> reservation.markNoShow("SYSTEM", AFTER_START);
        }
        return reservation;
    }

    @Test
    void bookingStartsBookedWithItsDetails() {
        Reservation reservation = booked();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BOOKED);
        assertThat(reservation.getStartsAt()).isEqualTo(STARTS);
        assertThat(reservation.getTableIds()).containsExactly(TABLE);
    }

    @Test
    void aBookedReservationIsEditable() {
        Reservation reservation = booked();
        Instant later = STARTS.plus(Duration.ofHours(1));

        reservation.update(new ReservationDetails(later, null, 6, "Ana Maria", null, null, null, Set.of()),
                BEFORE_START);

        assertThat(reservation.getStartsAt()).isEqualTo(later);
        assertThat(reservation.getPartySize()).isEqualTo(6);
        assertThat(reservation.getTableIds()).isEmpty();
    }

    @Test
    void seatingABookedReservationLinksTheOccupation() {
        Reservation reservation = booked();

        reservation.seat(OCCUPATION, AFTER_START);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.SEATED);
        assertThat(reservation.getOccupationId()).isEqualTo(OCCUPATION);
    }

    @Test
    void aBookedReservationMayBeSeatedBeforeItsStart() {
        Reservation reservation = booked();

        reservation.seat(OCCUPATION, BEFORE_START);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.SEATED);
    }

    @Test
    void cancellingABookedReservationKeepsTheReason() {
        Reservation reservation = booked();

        reservation.cancel("  guest called  ", BEFORE_START);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelReason()).isEqualTo("guest called");
    }

    @Test
    void aCancelNeedsAReason() {
        assertThatThrownBy(() -> booked().cancel(" ", BEFORE_START)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aNoShowAfterTheStartRecordsWhoMarkedIt() {
        Reservation reservation = booked();

        reservation.markNoShow("EMPLOYEE", AFTER_START);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getNoShowByActorKind()).isEqualTo("EMPLOYEE");
    }

    @Test
    void aNoShowBeforeTheStartIsRefusedAsNotStarted() {
        Reservation reservation = booked();

        assertThatThrownBy(() -> reservation.markNoShow("EMPLOYEE", BEFORE_START))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_STARTED");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BOOKED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "BOOKED", mode = EnumSource.Mode.EXCLUDE)
    void aReservationThatIsNoLongerBookedRefusesEveryCommand(ReservationStatus status) {
        for (Consumer<Reservation> command : java.util.List.<Consumer<Reservation>>of(
                r -> r.update(details(STARTS), AFTER_START),
                r -> r.seat(UUID.randomUUID(), AFTER_START),
                r -> r.cancel("late", AFTER_START),
                r -> r.markNoShow("EMPLOYEE", AFTER_START))) {
            Reservation reservation = in(status);

            assertThatThrownBy(() -> command.accept(reservation))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_BOOKED");
            assertThat(reservation.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void aPartyNeedsAtLeastOnePersonAndAContactName() {
        assertThatThrownBy(() -> Reservation.book(STORE,
                new ReservationDetails(STARTS, null, 0, "Ana", null, null, null, Set.of()), "EMPLOYEE", null,
                BEFORE_START)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Reservation.book(STORE,
                new ReservationDetails(STARTS, null, 2, " ", null, null, null, Set.of()), "EMPLOYEE", null,
                BEFORE_START)).isInstanceOf(BusinessRuleException.class);
    }
}
