package com.xavier.backend.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.exception.InvalidStoreException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class StoreTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void aNewStoreIsActiveAndItsBusinessDayStartsAtFiveByDefault() {
        Store store = Store.create("LJ01", "Baixa", "Africa/Maputo", null, NOW);

        assertThat(store.isActive()).isTrue();
        assertThat(store.getBusinessDayCutoff()).isEqualTo(LocalTime.of(5, 0));
        assertThat(store.getTimezone()).isEqualTo(ZoneId.of("Africa/Maputo"));
    }

    @Test
    void rejectsATimezoneThatIsNotARegionId() {
        assertThatThrownBy(() -> Store.create("LJ01", "Baixa", "Mars/Olympus", null, NOW))
                .isInstanceOf(InvalidStoreException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    void theBusinessDayFollowsTheStoresZoneAndCutoff() {
        Store store = Store.create("LJ01", "Baixa", "Africa/Maputo", LocalTime.of(4, 0), NOW);

        // 03:30 in Maputo is still the previous business day, 04:00 starts the new one
        assertThat(store.businessDayOf(Instant.parse("2026-11-04T01:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(store.businessDayOf(Instant.parse("2026-11-04T02:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    void anUpdateReplacesTheEditableFields() {
        Store store = Store.create("LJ01", "Baixa", "Africa/Maputo", null, NOW);

        store.update("LJ02", "Baixa Nova", "Europe/Lisbon", LocalTime.of(6, 0), false, NOW);

        assertThat(store.getCode()).isEqualTo("LJ02");
        assertThat(store.getName()).isEqualTo("Baixa Nova");
        assertThat(store.getTimezone()).isEqualTo(ZoneId.of("Europe/Lisbon"));
        assertThat(store.getBusinessDayCutoff()).isEqualTo(LocalTime.of(6, 0));
        assertThat(store.isActive()).isFalse();
    }
}
