package com.xavier.backend.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.shared.exception.CodedException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Seating through the normal table open (D126): {@code openOccupation(…, reservationId)} moves the reservation
 * BOOKED → SEATED in the caller's transaction and links the new occupation. Any table of the store may seat it.
 */
@DiningModuleTest
class ReservationSeatingTest {

    @org.springframework.beans.factory.annotation.Autowired
    DiningFixtures fixtures;

    final Instant soon = Instant.now().plus(Duration.ofMinutes(20));
    UUID store;
    UUID area;
    UUID table;

    @BeforeEach
    void aStoreWithATable() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
        table = fixtures.createTable(store, area, "M1");
    }

    @Test
    void openingATableForAReservationSeatsItAndLinksTheOccupation() throws Exception {
        UUID reservation = fixtures.book(store, soon, table);

        UUID order = fixtures.seatReservation(store, table, reservation);

        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("SEATED");
        assertThat(fixtures.reservationOccupation(reservation)).isEqualTo(fixtures.occupationOf(order));
        assertThat(fixtures.audits("RESERVATION_SEATED", reservation.toString(), store)).isEqualTo(1);
    }

    @Test
    void anyTableOfTheStoreMaySeatIt() throws Exception {
        UUID reservation = fixtures.book(store, soon, table);
        UUID other = fixtures.createTable(store, area, "M2");

        fixtures.seatReservation(store, other, reservation);

        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("SEATED");
    }

    @Test
    void aReservationThatIsNoLongerBookedIsRefusedAndTheTableStaysFree() throws Exception {
        UUID reservation = fixtures.book(store, soon, table);
        fixtures.seatReservation(store, table, reservation);
        UUID other = fixtures.createTable(store, area, "M2");

        assertThatThrownBy(() -> fixtures.seatReservation(store, other, reservation))
                .isInstanceOf(CodedException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_BOOKED");
        assertThat(fixtures.seat(store, other)).isNotNull(); // the refused opening was rolled back
    }

    @Test
    void aReservationOfAnotherStoreIsRefused() throws Exception {
        UUID otherStore = fixtures.newStore();
        UUID foreign = fixtures.book(otherStore, soon);

        assertThatThrownBy(() -> fixtures.seatReservation(store, table, foreign))
                .isInstanceOf(CodedException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_OTHER_STORE");
        assertThat(fixtures.reservationStatus(foreign)).isEqualTo("BOOKED");
    }

    @Test
    void anUnknownReservationIsNotFound() {
        assertThatThrownBy(() -> fixtures.seatReservation(store, table, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
