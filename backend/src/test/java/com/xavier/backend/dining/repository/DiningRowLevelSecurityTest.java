package com.xavier.backend.dining.repository;

import static com.xavier.backend.dining.AppRoleSession.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.dining.AppRoleSession;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A71/A72 for dining (D167, D168, D171): as the application role with a hand-written store scope (no
 * {@code AccessPolicy} in front), a store sees and writes only its own areas, tables, occupations, reservations and
 * settings; no scope moves one to another store; an unset scope fails closed; the D42 probe still finds another
 * store's table and reservation. Seeded as the owner.
 */
class DiningRowLevelSecurityTest {

    static final List<String> STORE_TABLES = List.of("dining_settings", "reservation", "reservation_table",
            "restaurant_table", "room_area", "table_occupation");

    final UUID storeA = UUID.randomUUID();
    final UUID storeB = UUID.randomUUID();
    final JdbcClient owner = OwnerDatabase.shared().jdbc();

    UUID areaA;
    UUID areaB;
    UUID tableA;
    UUID tableB;
    AppRoleSession app;

    @BeforeEach
    void seed() throws SQLException {
        areaA = area(owner, storeA);
        areaB = area(owner, storeB);
        tableA = table(storeA, areaA);
        tableB = table(storeB, areaB);
        app = AppRoleSession.open();
    }

    @AfterEach
    void close() throws SQLException {
        app.close();
    }

    @Test
    void everyDiningTableIsAStoreTable() {
        RowLevelSecurityCoverageTest.assertModuleClassified("dining");

        Map<String, String> policies = owner.sql("""
                        SELECT c.relname, p.polname FROM pg_catalog.pg_policy p
                            JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
                        WHERE c.relnamespace = 'dining'::regnamespace""")
                .query((row, n) -> Map.entry(row.getString(1), row.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(policies.keySet()).containsExactlyInAnyOrderElementsOf(STORE_TABLES);
        assertThat(policies.values()).containsOnly("store_scope");
    }

    @Test
    void aStoreScopeReadsOnlyItsStoresAreasAndTables() {
        assertThat(tablesSeen(app.stores(storeA))).containsExactly(tableA);
        assertThat(areasSeen(app.stores(storeA))).containsExactly(areaA);
        assertThat(tablesSeen(app.stores(storeA, storeB))).containsExactlyInAnyOrder(tableA, tableB);
        assertThat(areasSeen(app.allStores())).containsExactlyInAnyOrder(areaA, areaB);
        assertThat(tablesSeen(app.stores())).as("an explicit empty scope sees nothing, without an error").isEmpty();
    }

    @Test
    void anUnsetScopeFailsClosed() {
        app.unset();

        assertRefused(() -> tablesSeen(app), Kind.SCOPE_MISSING);
        assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM dining.dining_settings").query(Long.class).single(),
                Kind.SCOPE_MISSING);
    }

    @Test
    void aStoreScopeWritesNothingOfAnotherStore() {
        JdbcClient jdbc = app.stores(storeA).jdbc();

        assertRefused(() -> area(jdbc, storeB), Kind.WRITE_REFUSED);
        assertRefused(() -> jdbc.sql("""
                        INSERT INTO dining.dining_settings (store_id, updated_at, version) VALUES (?, now(), 0)""")
                .param(storeB).update(), Kind.WRITE_REFUSED);
        assertThat(jdbc.sql("UPDATE dining.restaurant_table SET seats = 9 WHERE id = ?").param(tableB).update())
                .as("another store's table is not there to update").isZero();
        assertThat(jdbc.sql("UPDATE dining.restaurant_table SET seats = 9 WHERE id = ?").param(tableA).update())
                .isOne();
    }

    @Test
    void noScopeMovesAnAreaOrItsSettingsToAnotherStore() {
        owner.sql("INSERT INTO dining.dining_settings (store_id, updated_at, version) VALUES (?, now(), 0)")
                .param(storeA).update();
        String moveArea = "UPDATE dining.room_area SET store_id = ? WHERE id = ?";

        assertRefused(() -> app.stores(storeA).jdbc().sql(moveArea).param(storeB).param(areaA).update(),
                Kind.STORE_CHANGE);
        assertRefused(() -> app.allStores().jdbc().sql(moveArea).param(storeB).param(areaA).update(),
                Kind.STORE_CHANGE);
        assertRefused(() -> app.allStores().jdbc().sql("UPDATE dining.dining_settings SET store_id = ? "
                + "WHERE store_id = ?").param(storeB).param(storeA).update(), Kind.STORE_CHANGE);
    }

    /** D42 under RLS: hidden from store A's read, yet its store is probed (then 403, not 404). */
    @Test
    void theProbeFindsAnotherStoresAreaTableAndReservation() {
        UUID reservationB = UUID.randomUUID();
        owner.sql("""
                INSERT INTO dining.reservation (id, store_id, starts_at, party_size, contact_name, status,
                    created_by_actor_kind, created_at, updated_at, version)
                VALUES (?, ?, now() + interval '1 day', 2, 'Ana', 'BOOKED', 'EMPLOYEE', now(), now(), 0)""")
                .param(reservationB).param(storeB).update();
        StoreProbe probe = new StoreProbe(app.stores(storeA).jdbc());

        assertThat(probe.storeOf("dining.room_area", areaB)).contains(storeB);
        assertThat(probe.storeOf("dining.restaurant_table", tableB)).contains(storeB);
        assertThat(probe.storeOf("dining.reservation", reservationB)).contains(storeB);
    }

    private List<UUID> tablesSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM dining.restaurant_table WHERE id IN (?, ?)").param(tableA)
                .param(tableB).query(UUID.class).list();
    }

    private List<UUID> areasSeen(AppRoleSession scope) {
        return scope.jdbc().sql("SELECT id FROM dining.room_area WHERE id IN (?, ?)").param(areaA).param(areaB)
                .query(UUID.class).list();
    }

    private static UUID area(JdbcClient jdbc, UUID store) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO dining.room_area (id, store_id, code, name, active, version, created_at, updated_at)
                VALUES (?, ?, ?, 'Sala', true, 0, now(), now())""")
                .param(id).param(store).param("A" + id.toString().substring(0, 8)).update();
        return id;
    }

    private UUID table(UUID store, UUID area) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO dining.restaurant_table (id, store_id, area_id, code, seats, active, version, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, 4, true, 0, now(), now())""")
                .param(id).param(store).param(area).param("T" + id.toString().substring(0, 8)).update();
        return id;
    }
}
