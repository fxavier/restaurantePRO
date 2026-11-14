package com.xavier.backend.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * D168: the D42 probe sees every store, so another store's resource still answers 403, not 404, while a plain read of
 * the same row under the actor's scope sees nothing. As the application role, scoped to store A.
 */
class StoreProbeTest {

    static final UUID STORE_A = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190a000-0000-7000-8000-0000000000bb");
    static final String SCHEMA = "rls_scratch_probe";

    static UUID ofB;
    static Connection connection;
    static JdbcClient storeA;

    @BeforeAll
    static void scratch() throws SQLException {
        dropScratch();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("CREATE SCHEMA " + SCHEMA).update();
            owner.sql("CREATE TABLE " + SCHEMA + ".item (id uuid PRIMARY KEY, store_id uuid NOT NULL)").update();
            owner.sql("SELECT shared.rls_protect_store_table('" + SCHEMA + ".item')").query().listOfRows();
            owner.sql("CREATE TABLE " + SCHEMA + ".open_item (id uuid PRIMARY KEY, store_id uuid NOT NULL)").update();
            owner.sql("CREATE TABLE " + SCHEMA + ".storeless (id uuid PRIMARY KEY)").update();
            owner.sql("ALTER TABLE " + SCHEMA + ".storeless ENABLE ROW LEVEL SECURITY").update();
        });
        ofB = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("INSERT INTO " + SCHEMA + ".item VALUES (?, ?)").param(ofB).param(STORE_B)
                .update();
        connection = DriverManager.getConnection(TestcontainersConfiguration.postgres().getJdbcUrl(),
                TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword());
        connection.createStatement().execute("SELECT set_config('app.all_stores', 'off', false), "
                + "set_config('app.store_ids', '{" + STORE_A + "}', false), "
                + "set_config('app.scope_origin', 'account:test', false)");
        storeA = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void dropScratch() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        OwnerDatabase.shared().jdbc().sql("DELETE FROM shared.rls_protected_table WHERE table_name::text LIKE ?")
                .param(SCHEMA + ".%").update();
        OwnerDatabase.shared().jdbc().sql("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE").update();
    }

    @Test
    void anotherStoresRowIsHiddenFromAReadButItsStoreIsProbed() {
        assertThat(storeA.sql("SELECT count(*) FROM " + SCHEMA + ".item WHERE id = ?").param(ofB).query(Long.class)
                .single()).as("row-level security hides it").isZero();

        assertThat(new StoreProbe(storeA).storeOf(SCHEMA + ".item", ofB)).contains(STORE_B);
    }

    @Test
    void aMissingRowHasNoStore() {
        assertThat(new StoreProbe(storeA).storeOf(SCHEMA + ".item", UUID.randomUUID())).isEmpty();
    }

    @Test
    void aTableWithoutRowLevelSecurityOrWithoutAStoreIsRefused() {
        StoreProbe probe = new StoreProbe(storeA);

        assertThatThrownBy(() -> probe.storeOf(SCHEMA + ".open_item", ofB)).hasMessageContaining("row-level security");
        assertThatThrownBy(() -> probe.storeOf(SCHEMA + ".storeless", ofB)).hasMessageContaining("no store_id");
    }
}
