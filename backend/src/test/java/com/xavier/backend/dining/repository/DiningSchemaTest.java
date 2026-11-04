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
