package com.xavier.backend.dining;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two hosts seat the same reservation at two tables at the same instant (latch): the reservation row lock makes it
 * a compare-and-set, so one opening wins and the other is 409 {@code RESERVATION_NOT_BOOKED} and rolls back its
 * occupation.
 */
@DiningModuleTest
class ReservationSeatRaceTest {

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void twoConcurrentSeatingsOfOneReservationLetOneWin() throws Exception {
        UUID store = fixtures.newStore();
        UUID area = fixtures.createArea(store, "SALA");
        UUID m1 = fixtures.createTable(store, area, "M1");
        UUID m2 = fixtures.createTable(store, area, "M2");
        UUID reservation = fixtures.book(store, Instant.now().plus(Duration.ofMinutes(15)), m1);
        List<UUID> tables = List.of(m1, m2);
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

        List<Integer> statuses = DiningFixtures.race(() -> {
            UUID table = tables.get(next.getAndIncrement());
            try {
                fixtures.seatReservation(store, table, reservation);
                return 200;
            } catch (ConflictException e) {
                assertThat(e.code()).isEqualTo("RESERVATION_NOT_BOOKED");
                return 409;
            }
        });

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("SEATED");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM dining.table_occupation
                        WHERE table_id IN (:m1, :m2) AND closed_at IS NULL""")
                .param("m1", m1).param("m2", m2).query(Long.class).single()).isEqualTo(1);
    }
}
