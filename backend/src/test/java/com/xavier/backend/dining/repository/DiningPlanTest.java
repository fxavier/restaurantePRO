package com.xavier.backend.dining.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.dining.AppRoleSession;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * D169/D171 plan of the hot dining read under row-level security, as the application role in one store's scope: the
 * open order of a table ({@code TableOccupationRepository.findByTableIdAndClosedAtIsNull}, every opening, move and
 * table state) stays on the partial unique index {@code table_occupation_open_table_uk}; the policy is an InitPlan
 * filter on top. Deterministic seed (one store, 50 tables, each occupied now and 99 times before), then
 * {@code ANALYZE} as the owner and {@code EXPLAIN} in one transaction with {@code enable_seqscan = off}.
 */
class DiningPlanTest {

    static UUID store;
    static UUID table;
    static AppRoleSession app;

    @BeforeAll
    static void seed() throws SQLException {
        store = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("""
                    INSERT INTO dining.room_area (id, store_id, code, name, active, version, created_at, updated_at)
                    VALUES (?, ?, 'PLAN', 'Plan', true, 0, now(), now())""").param(area).param(store).update();
            owner.sql("""
                    INSERT INTO dining.restaurant_table (id, store_id, area_id, code, seats, active, version,
                        created_at, updated_at)
                    SELECT uuidv7(), :store, :area, 'P' || n, 4, true, 0, now(), now()
                    FROM generate_series(1, 50) AS n""").param("store", store).param("area", area).update();
            owner.sql("""
                    INSERT INTO dining.table_occupation (id, store_id, table_id, order_id, guests, opened_at,
                        opened_by_employee_id, closed_at, close_reason, version)
                    SELECT uuidv7(), t.store_id, t.id, gen_random_uuid(), 2, TIMESTAMPTZ '2020-01-01 12:00Z',
                        gen_random_uuid(), CASE WHEN n > 1 THEN TIMESTAMPTZ '2020-01-01 13:00Z' END,
                        CASE WHEN n > 1 THEN 'ORDER_CLOSED' END, 0
                    FROM dining.restaurant_table t, generate_series(1, 100) AS n
                    WHERE t.store_id = :store""").param("store", store).update();
        });
        table = OwnerDatabase.shared().jdbc().sql("SELECT id FROM dining.restaurant_table WHERE store_id = ? "
                + "AND code = 'P1'").param(store).query(UUID.class).single();
        app = AppRoleSession.open().stores(store);
    }

    @AfterAll
    static void close() throws SQLException {
        app.close();
    }

    @Test
    void theOpenOccupationOfATableStaysOnItsPartialUniqueIndex() {
        OwnerDatabase.shared().jdbc().sql("ANALYZE dining.table_occupation").update();
        String plan = app.inTransaction(jdbc -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("""
                            EXPLAIN SELECT * FROM dining.table_occupation
                            WHERE table_id = :table AND closed_at IS NULL""")
                    .param("table", table).query(String.class).list());
        });

        assertThat(plan).as("the store_scope policy, once per statement").contains("InitPlan")
                .containsPattern("(Index Scan using|Bitmap Index Scan on) table_occupation_open_table_uk")
                .containsPattern("Index Cond: \\(table_id = '[0-9a-f-]+'::uuid\\)");
    }
}
