package com.xavier.backend.dining.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.exception.AreaNotInStoreException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RestaurantTableTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final UUID STORE = UUID.randomUUID();
    static final UUID OTHER_STORE = UUID.randomUUID();

    @Test
    void aTableBelongsToAnAreaOfItsOwnStore() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);

        RestaurantTable table = RestaurantTable.create(STORE, area, " M1 ", 4, NOW);

        assertThat(table.getStoreId()).isEqualTo(STORE);
        assertThat(table.getCode()).isEqualTo("M1");
        assertThat(table.isActive()).isTrue();
    }

    @Test
    void aTableCannotUseAnAreaOfAnotherStore() {
        RoomArea foreignArea = RoomArea.create(OTHER_STORE, "SALA", "Sala", NOW);

        assertThatThrownBy(() -> RestaurantTable.create(STORE, foreignArea, "M1", 4, NOW))
                .isInstanceOf(AreaNotInStoreException.class);
    }

    @Test
    void aTableCannotMoveToAnAreaOfAnotherStore() {
        RestaurantTable table = RestaurantTable.create(STORE, RoomArea.create(STORE, "SALA", "Sala", NOW), "M1", 4, NOW);
        RoomArea foreignArea = RoomArea.create(OTHER_STORE, "ESPL", "Esplanada", NOW);

        assertThatThrownBy(() -> table.update(foreignArea, "M1", 4, true, NOW))
                .isInstanceOf(AreaNotInStoreException.class);
    }

    @Test
    void aTableSeatsAtLeastOnePerson() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);

        assertThatThrownBy(() -> RestaurantTable.create(STORE, area, "M1", 0, NOW))
                .isInstanceOf(InvalidDiningSetupException.class);
    }

    @Test
    void aTableNeedsACode() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);

        assertThatThrownBy(() -> RestaurantTable.create(STORE, area, " ", 2, NOW))
                .isInstanceOf(InvalidDiningSetupException.class);
    }
}
