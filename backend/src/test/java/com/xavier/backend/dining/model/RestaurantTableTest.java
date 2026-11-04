package com.xavier.backend.dining.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.exception.AreaNotInStoreException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.exception.TableUnavailableException;
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

    static final UUID EMPLOYEE = UUID.randomUUID();

    @Test
    void lockingRecordsWhoLockedTheTableWhenAndWhy() {
        RestaurantTable table = table();

        boolean changed = table.lock(EMPLOYEE, " consumo em disputa ", NOW);

        assertThat(changed).isTrue();
        assertThat(table.isLocked()).isTrue();
        assertThat(table.getLockedAt()).isEqualTo(NOW);
        assertThat(table.getLockedByEmployeeId()).isEqualTo(EMPLOYEE);
        assertThat(table.getLockReason()).isEqualTo("consumo em disputa");
    }

    @Test
    void lockingALockedTableKeepsTheFirstLock() {
        RestaurantTable table = table();
        table.lock(EMPLOYEE, "first", NOW);

        boolean changed = table.lock(UUID.randomUUID(), "second", NOW.plusSeconds(60));

        assertThat(changed).isFalse();
        assertThat(table.getLockReason()).isEqualTo("first");
        assertThat(table.getLockedByEmployeeId()).isEqualTo(EMPLOYEE);
    }

    @Test
    void aLockNeedsAReason() {
        assertThatThrownBy(() -> table().lock(EMPLOYEE, " ", NOW)).isInstanceOf(InvalidDiningSetupException.class);
    }

    @Test
    void unlockingClearsTheLockAndUnlockingAFreeTableChangesNothing() {
        RestaurantTable table = table();
        table.lock(EMPLOYEE, "reason", NOW);

        assertThat(table.unlock(NOW)).isTrue();
        assertThat(table.isLocked()).isFalse();
        assertThat(table.getLockReason()).isNull();
        assertThat(table.unlock(NOW)).isFalse();
    }

    @Test
    void anActiveUnlockedTableOfTheSessionStoreTakesAnOccupation() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);

        RestaurantTable.create(STORE, area, "M1", 4, NOW).requireOpenable(STORE, area);
    }

    @Test
    void aTableOfAnotherStoreIsRefusedAsStoreMismatch() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);

        assertRefused(() -> RestaurantTable.create(STORE, area, "M1", 4, NOW).requireOpenable(OTHER_STORE, area),
                "STORE_MISMATCH");
    }

    @Test
    void anInactiveTableOrAnInactiveAreaIsRefusedAsTableInactive() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);
        RestaurantTable inactive = RestaurantTable.create(STORE, area, "M1", 4, NOW);
        inactive.update(area, "M1", 4, false, NOW);
        RoomArea inactiveArea = RoomArea.create(STORE, "ESPL", "Esplanada", NOW);
        RestaurantTable inClosedArea = RestaurantTable.create(STORE, inactiveArea, "E1", 4, NOW);
        inactiveArea.update("ESPL", "Esplanada", false, NOW);

        assertRefused(() -> inactive.requireOpenable(STORE, area), "TABLE_INACTIVE");
        assertRefused(() -> inClosedArea.requireOpenable(STORE, inactiveArea), "TABLE_INACTIVE");
    }

    @Test
    void aLockedTableIsRefusedAsTableLocked() {
        RoomArea area = RoomArea.create(STORE, "SALA", "Sala", NOW);
        RestaurantTable table = RestaurantTable.create(STORE, area, "M1", 4, NOW);
        table.lock(EMPLOYEE, "reason", NOW);

        assertRefused(() -> table.requireOpenable(STORE, area), "TABLE_LOCKED");
    }

    private static void assertRefused(Runnable check, String code) {
        assertThatThrownBy(check::run).isInstanceOf(TableUnavailableException.class)
                .satisfies(e -> assertThat(((TableUnavailableException) e).code()).isEqualTo(code))
                .satisfies(e -> assertThat(((TableUnavailableException) e).getStatusCode().value()).isEqualTo(422));
    }

    private static RestaurantTable table() {
        return RestaurantTable.create(STORE, RoomArea.create(STORE, "SALA", "Sala", NOW), "M1", 4, NOW);
    }
}
