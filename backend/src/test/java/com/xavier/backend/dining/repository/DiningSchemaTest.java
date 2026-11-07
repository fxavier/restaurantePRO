package com.xavier.backend.dining.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.DiningModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The database enforces the dining invariants on its own, even for writes that bypass the code. */
@DiningModuleTest
class DiningSchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void theDatabaseRejectsATableWhoseAreaIsInAnotherStore() {
        UUID storeA = UUID.randomUUID();
        UUID areaOfStoreB = insertArea(UUID.randomUUID());

        assertThatThrownBy(() -> insertTable(storeA, areaOfStoreB, 4))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("restaurant_table_area_same_store_fk");
    }

    @Test
    void theDatabaseRejectsATableWithoutSeats() {
        UUID store = UUID.randomUUID();
        UUID area = insertArea(store);

        assertThatThrownBy(() -> insertTable(store, area, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("restaurant_table_seats_positive_ck");
        assertThatCode(() -> insertTable(store, area, 1)).doesNotThrowAnyException();
    }

    @Test
    void theDatabaseRejectsASeatedReservationWithoutItsOccupation() {
        assertThatThrownBy(() -> insertReservation("SEATED", "'841234567'", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservation_seated_ck");
    }

    @Test
    void theDatabaseRejectsACancelledReservationWithoutAReason() {
        assertThatThrownBy(() -> insertReservation("CANCELLED", "NULL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservation_cancelled_ck");
        assertThatCode(() -> insertReservation("CANCELLED", "NULL", "guest called")).doesNotThrowAnyException();
    }

    @Test
    void theDatabaseKeepsOnlyDigitsAsTheContactPhone() {
        assertThatThrownBy(() -> insertReservation("BOOKED", "'+258 84'", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservation_contact_phone_digits_ck");
    }

    void insertReservation(String status, String phoneSql, String cancelReason) {
        jdbc.sql("""
                        INSERT INTO dining.reservation (id, store_id, starts_at, party_size, contact_name,
                            contact_phone_digits, status, cancelled_at, cancel_reason, created_by_actor_kind,
                            created_at, updated_at, version)
                        VALUES (:id, :store, now(), 2, 'Ana', %s, :status,
                            CASE WHEN :status = 'CANCELLED' THEN now() END, :reason, 'EMPLOYEE', now(), now(), 0)"""
                        .formatted(phoneSql))
                .param("id", UUID.randomUUID()).param("store", UUID.randomUUID()).param("status", status)
                .param("reason", cancelReason).update();
    }

    UUID insertArea(UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO dining.room_area (id, store_id, code, name, active, version, created_at, updated_at)
                        VALUES (:id, :store, :code, 'Sala', true, 0, now(), now())""")
                .param("id", id).param("store", store).param("code", "A" + id.toString().substring(0, 8)).update();
        return id;
    }

    void insertTable(UUID store, UUID area, int seats) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO dining.restaurant_table
                            (id, store_id, area_id, code, seats, active, version, created_at, updated_at)
                        VALUES (:id, :store, :area, :code, :seats, true, 0, now(), now())""")
                .param("id", id).param("store", store).param("area", area)
                .param("code", "T" + id.toString().substring(0, 8)).param("seats", seats).update();
    }
}
