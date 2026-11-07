package com.xavier.backend.dining;

import static com.xavier.backend.dining.DiningActors.inStore;
import static com.xavier.backend.dining.DiningActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.model.dto.TableState;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The map's "reserved" indicator (D126, P47): from the store's window before a BOOKED reservation's start until its
 * no-show grace, in absolute time, independent of occupation and lock. Starts sit tens of minutes from the
 * boundaries, so the real clock of the context cannot cross one during a test; the exact boundaries are in
 * {@code ReservationWindowTest}.
 */
@DiningModuleTest
class ReservationIndicatorTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    DiningApi dining;

    final UUID waiter = UUID.randomUUID();
    final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID store;
    UUID area;

    @BeforeEach
    void aStore() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
    }

    @Test
    void aReservationStartingWithinTheWindowMarksItsTable() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        Instant startsAt = now.plus(Duration.ofMinutes(60));
        fixtures.book(store, startsAt, table);

        map(store).andExpect(jsonPath(at(table, "reserved"), contains(true)))
                .andExpect(jsonPath(at(table, "nextReservationAt"), contains(startsAt.toString())))
                .andExpect(jsonPath(at(table, "status"), contains("FREE")));
    }

    @Test
    void aReservationBeyondTheWindowOrPastItsGraceDoesNotMarkItsTable() throws Exception {
        UUID later = fixtures.createTable(store, area, "M1");
        UUID lapsed = fixtures.createTable(store, area, "M2");
        fixtures.book(store, now.plus(Duration.ofMinutes(180)), later);
        fixtures.moveStart(fixtures.book(store, now.plus(Duration.ofMinutes(5)), lapsed),
                now.minus(Duration.ofMinutes(45)));

        map(store).andExpect(jsonPath(at(later, "reserved"), contains(false)))
                .andExpect(jsonPath(at(later, "nextReservationAt"), contains(nullValue())))
                .andExpect(jsonPath(at(lapsed, "reserved"), contains(false)));
    }

    @Test
    void aStartedReservationStaysMarkedDuringItsGrace() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        fixtures.book(store, now.minus(Duration.ofMinutes(10)), table);

        map(store).andExpect(jsonPath(at(table, "reserved"), contains(true)));
    }

    @Test
    void theEarliestIndicatedReservationIsTheNextOne() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        Instant sooner = now.plus(Duration.ofMinutes(30));
        fixtures.book(store, now.plus(Duration.ofMinutes(90)), table);
        fixtures.book(store, sooner, table);

        map(store).andExpect(jsonPath(at(table, "nextReservationAt"), contains(sooner.toString())));
    }

    @Test
    void theIndicatorIsIndependentOfOccupationAndLock() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        fixtures.book(store, now.plus(Duration.ofMinutes(60)), table);
        fixtures.seat(store, table);
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + table + "/lock")
                        .with(pos(store, waiter, Permission.TABLE_LOCK)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "VIP"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(true));

        map(store).andExpect(jsonPath(at(table, "reserved"), contains(true)))
                .andExpect(jsonPath(at(table, "status"), contains("OCCUPIED")))
                .andExpect(jsonPath(at(table, "locked"), contains(true)));
    }

    @Test
    void aCancelledReservationNoLongerMarksItsTable() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        UUID reservation = fixtures.book(store, now.plus(Duration.ofMinutes(60)), table);
        mvc.perform(post(DiningFixtures.POS_RESERVATIONS + "/" + reservation + "/cancel")
                        .with(pos(store, waiter, Permission.RESERVATION_MANAGE)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version": 0, "reason": "guest called"}"""))
                .andExpect(status().isOk());

        map(store).andExpect(jsonPath(at(table, "reserved"), contains(false)));
    }

    @Test
    void theStoreWindowSettingDecidesHowEarlyTheIndicatorShows() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        fixtures.book(store, now.plus(Duration.ofMinutes(60)), table);
        mvc.perform(put("/api/v1/backoffice/dining/settings/" + store)
                        .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reservationWindowMinutes": 30, "noShowGraceMinutes": 30}"""))
                .andExpect(status().isOk());

        map(store).andExpect(jsonPath(at(table, "reserved"), contains(false)));
    }

    @Test
    void theWindowIsAbsoluteTimeWhateverTheStoreZone() throws Exception {
        UUID farEast = fixtures.newStore("Pacific/Kiritimati"); // UTC+14: another local date than UTC
        UUID table = fixtures.createTable(farEast, fixtures.createArea(farEast, "SALA"), "M1");
        fixtures.book(farEast, now.plus(Duration.ofMinutes(60)), table);

        map(farEast).andExpect(jsonPath(at(table, "reserved"), contains(true)));
    }

    @Test
    void theTableStateForOrdersCarriesTheIndicator() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        Instant startsAt = now.plus(Duration.ofMinutes(60));
        fixtures.book(store, startsAt, table);

        TableState state = dining.tableState(table);

        assertThat(state.reserved()).isTrue();
        assertThat(state.nextReservationAt()).isEqualTo(startsAt);
    }

    ResultActions map(UUID storeId) throws Exception {
        return mvc.perform(get(DiningFixtures.POS_TABLES).with(pos(storeId, waiter, Permission.DINING_VIEW)))
                .andExpect(status().isOk());
    }

    static String at(UUID table, String field) {
        return "$.areas[*].tables[?(@.id == '" + table + "')]." + field;
    }
}
