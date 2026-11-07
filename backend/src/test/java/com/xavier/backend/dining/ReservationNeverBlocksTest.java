package com.xavier.backend.dining;

import static com.xavier.backend.dining.DiningActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** §8.3, D126: a reservation never blocks a table open (a walk-in) or a lock, and it stays BOOKED. */
@DiningModuleTest
class ReservationNeverBlocksTest {

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    MockMvc mvc;

    UUID store;
    UUID table;
    UUID reservation;

    @BeforeEach
    void aTableReservedSoon() throws Exception {
        store = fixtures.newStore();
        table = fixtures.createTable(store, fixtures.createArea(store, "SALA"), "M1");
        reservation = fixtures.book(store, Instant.now().plus(Duration.ofMinutes(30)), table);
    }

    @Test
    void aBookedTableOpensForAWalkIn() {
        UUID order = fixtures.seat(store, table);

        assertThat(fixtures.occupationOf(order)).isNotNull();
        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("BOOKED");
    }

    @Test
    void aBookedTableCanBeLocked() throws Exception {
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + table + "/lock")
                        .with(pos(store, UUID.randomUUID(), Permission.TABLE_LOCK)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "cleaning"}"""))
                .andExpect(status().isOk());

        assertThat(fixtures.reservationStatus(reservation)).isEqualTo("BOOKED");
    }
}
