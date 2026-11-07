package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.inStore;
import static com.xavier.backend.dining.DiningActors.pos;
import static com.xavier.backend.dining.DiningFixtures.POS_RESERVATIONS;
import static com.xavier.backend.dining.DiningFixtures.reservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The reservation calendar and its commands (D126, P47) over HTTP: permissions, codes, audit, idempotency. */
@DiningModuleTest
class ReservationHttpTest {

    static final String BACKOFFICE = "/api/v1/backoffice/reservations";

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    final UUID host = UUID.randomUUID();
    final Instant tomorrow = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
    UUID store;
    UUID table;

    @BeforeEach
    void aStoreWithATable() throws Exception {
        store = fixtures.newStore();
        table = fixtures.createTable(store, fixtures.createArea(store, "SALA"), "M1");
    }

    @Test
    void aHostBooksAReservationWithItsContactAndItIsAudited() throws Exception {
        String json = mvc.perform(create(reservation(tomorrow, null, table)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.contactPhoneDigits").value("258841234567"))
                .andExpect(jsonPath("$.tableIds[0]").value(table.toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(fixtures.audits("RESERVATION_BOOKED", id(json), store)).isEqualTo(1);
    }

    @Test
    void aReplayedBookingWithTheSameKeyCreatesOneReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(create(reservation(tomorrow, null, table), key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(create(reservation(tomorrow, null, table), key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(id(second)).isEqualTo(id(first));
    }

    @Test
    void aTableOfAnotherStoreIsRefused() throws Exception {
        UUID other = fixtures.newStore();
        UUID foreign = fixtures.createTable(other, fixtures.createArea(other, "SALA"), "M1");

        mvc.perform(create(reservation(tomorrow, null, foreign)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_OTHER_STORE"));
    }

    @Test
    void anUnknownCustomerIsRefusedAndAKnownOneIsKept() throws Exception {
        mvc.perform(create(reservation(tomorrow, UUID.randomUUID(), table)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

        UUID customer = fixtures.newCustomer();
        mvc.perform(create(reservation(tomorrow, customer, table)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customer.toString()));
    }

    @Test
    void twoReservationsMayOverlapOnOneTable() throws Exception {
        mvc.perform(create(reservation(tomorrow, null, table))).andExpect(status().isCreated());
        mvc.perform(create(reservation(tomorrow, null, table))).andExpect(status().isCreated());
    }

    @Test
    void bookingNeedsReservationManage() throws Exception {
        mvc.perform(post(POS_RESERVATIONS).with(pos(store, host, Permission.RESERVATION_VIEW)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reservation(tomorrow, null, table)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBookedReservationIsEditedWithItsVersionAndAStaleOneIsRefused() throws Exception {
        UUID reservation = fixtures.book(store, tomorrow, table);
        Instant later = tomorrow.plus(Duration.ofHours(1));

        mvc.perform(edit(reservation, 0, later)).andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value(later.toString()))
                .andExpect(jsonPath("$.partySize").value(6))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(edit(reservation, 0, later)).andExpect(status().isConflict());

        assertThat(fixtures.audits("RESERVATION_UPDATED", reservation.toString(), store)).isEqualTo(1);
    }

    @Test
    void aReservationOfAnotherStoreIsForbiddenAndAnUnknownOneIsNotFound() throws Exception {
        UUID other = fixtures.newStore();
        UUID foreign = fixtures.book(other, tomorrow);

        mvc.perform(edit(foreign, 0, tomorrow)).andExpect(status().isForbidden());
        mvc.perform(edit(UUID.randomUUID(), 0, tomorrow)).andExpect(status().isNotFound());
    }

    @Test
    void aCancelledReservationIsNoLongerEditable() throws Exception {
        UUID reservation = fixtures.book(store, tomorrow, table);

        mvc.perform(command(reservation, "cancel", """
                        {"version": 0, "reason": "guest called"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("guest called"));
        mvc.perform(edit(reservation, 1, tomorrow))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_BOOKED"));

        assertThat(fixtures.audits("RESERVATION_CANCELLED", reservation.toString(), store)).isEqualTo(1);
    }

    @Test
    void aCancelNeedsAReason() throws Exception {
        UUID reservation = fixtures.book(store, tomorrow, table);

        mvc.perform(command(reservation, "cancel", """
                        {"version": 0, "reason": " "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNoShowBeforeTheStartIsRefusedAndAfterItIsRecorded() throws Exception {
        UUID future = fixtures.book(store, tomorrow, table);
        UUID started = fixtures.book(store, Instant.now().minus(Duration.ofMinutes(5)), table);

        mvc.perform(command(future, "no-show", """
                        {"version": 0}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_STARTED"));
        mvc.perform(command(started, "no-show", """
                        {"version": 0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.noShowByActorKind").value("EMPLOYEE"));

        assertThat(fixtures.audits("RESERVATION_NO_SHOW", started.toString(), store)).isEqualTo(1);
    }

    @Test
    void thePosCalendarListsTheDayInTheStoreZone() throws Exception {
        UUID farEast = fixtures.newStore("Pacific/Kiritimati"); // UTC+14
        Instant elevenUtc = Instant.parse("2027-03-10T11:00:00Z"); // 2027-03-11 01:00 local
        UUID reservation = fixtures.book(farEast, elevenUtc);

        mvc.perform(get(POS_RESERVATIONS).param("date", "2027-03-11")
                        .with(pos(farEast, host, Permission.RESERVATION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(reservation.toString()));
        mvc.perform(get(POS_RESERVATIONS).param("date", "2027-03-10")
                        .with(pos(farEast, host, Permission.RESERVATION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theBackOfficeCalendarListsARangeOfAtMost92Days() throws Exception {
        UUID reservation = fixtures.book(store, Instant.parse("2027-05-02T12:00:00Z"));

        mvc.perform(get(BACKOFFICE).param("storeId", store.toString()).param("from", "2027-05-01")
                        .param("to", "2027-05-02").with(inStore(store, Permission.RESERVATION_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reservation.toString()));
        mvc.perform(get(BACKOFFICE).param("storeId", store.toString()).param("from", "2027-01-01")
                        .param("to", "2027-04-03").with(inStore(store, Permission.RESERVATION_VIEW)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(get(BACKOFFICE).param("storeId", store.toString()).param("from", "2027-05-01")
                        .param("to", "2027-05-02").with(inStore(UUID.randomUUID(), Permission.RESERVATION_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStoreSettingsAreReadAndUpdatedWithTheirVersion() throws Exception {
        String path = "/api/v1/backoffice/dining/settings/" + store;
        mvc.perform(get(path).with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationWindowMinutes").value(120))
                .andExpect(jsonPath("$.noShowGraceMinutes").value(30));

        mvc.perform(settings(path, 60, 15, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationWindowMinutes").value(60))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(settings(path, 90, 15, 0L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(settings(path, 90, 15, 0L)).andExpect(status().isConflict());
        mvc.perform(settings(path, 0, 15, 1L)).andExpect(status().isBadRequest());

        assertThat(fixtures.audits("DINING_SETTINGS_UPDATED", store.toString(), store)).isEqualTo(2);
    }

    MockHttpServletRequestBuilder settings(String path, int window, int grace, Long version) {
        return put(path).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reservationWindowMinutes": %d, "noShowGraceMinutes": %d, "version": %s}"""
                        .formatted(window, grace, version));
    }

    MockHttpServletRequestBuilder create(String body) {
        return create(body, UUID.randomUUID().toString());
    }

    MockHttpServletRequestBuilder create(String body, String key) {
        return post(POS_RESERVATIONS).with(pos(store, host, Permission.RESERVATION_MANAGE)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    MockHttpServletRequestBuilder edit(UUID reservation, long version, Instant startsAt) {
        return put(POS_RESERVATIONS + "/" + reservation).with(pos(store, host, Permission.RESERVATION_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"version": %d, "startsAt": "%s", "partySize": 6, "contactName": "Ana Maria",
                         "tableIds": ["%s"]}""".formatted(version, startsAt, table));
    }

    MockHttpServletRequestBuilder command(UUID reservation, String command, String body) {
        return post(POS_RESERVATIONS + "/" + reservation + "/" + command)
                .with(pos(store, host, Permission.RESERVATION_MANAGE)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String id(String json) {
        return DiningFixtures.JSON.readTree(json).get("id").asString();
    }
}
