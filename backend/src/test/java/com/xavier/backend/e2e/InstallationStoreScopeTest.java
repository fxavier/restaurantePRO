package com.xavier.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * D171 in an e2e installation ({@link Installation.FreshDatabase}, its own database): the acceptance tests' assertion
 * SQL in the test body has the fixture fallback (every store), while any other thread keeps production's unset scope.
 * Before, such a context had the production fallback on the JUnit thread too, so every assertion on a protected table
 * raised {@code RS001}. On a scratch table protected by {@code shared.rls_protect_store_table}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Installation.FreshDatabase.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InstallationStoreScopeTest {

    static final String TABLE = "rls_scratch_e2e.item";
    static final String READ = "SELECT current_setting('app.scope_origin', true) || ':' || coalesce(string_agg(label, ',' "
            + "ORDER BY label), '') FROM " + TABLE;

    @DynamicPropertySource
    static void bootstrapSettings(DynamicPropertyRegistry registry) throws IOException {
        Installation.bootstrap(registry, "Restaurante Escopo", "initial " + UUID.randomUUID());
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PostgreSQLContainer freshPostgres;

    @BeforeEach
    void scratchTable() {
        JdbcClient owner = Installation.owner(freshPostgres);
        owner.sql("DELETE FROM shared.rls_protected_table WHERE table_name::text = ?").param(TABLE).update();
        owner.sql("DROP SCHEMA IF EXISTS rls_scratch_e2e CASCADE").update();
        owner.sql("CREATE SCHEMA rls_scratch_e2e").update();
        owner.sql("CREATE TABLE " + TABLE + " (id uuid PRIMARY KEY, store_id uuid NOT NULL, label text)").update();
        owner.sql("SELECT shared.rls_protect_store_table(CAST(? AS regclass))").param(TABLE).query().listOfRows();
        owner.sql("INSERT INTO " + TABLE + " VALUES (?, ?, 'a'), (?, ?, 'b')").param(UUID.randomUUID())
                .param(UUID.randomUUID()).param(UUID.randomUUID()).param(UUID.randomUUID()).update();
    }

    @Test
    void assertionSqlInTheTestBodySeesEveryStore() {
        assertThat(jdbc.sql(READ).query(String.class).single()).isEqualTo("test-fixture:a,b");
    }

    @Test
    void anotherThreadHasNoStoreScope() {
        assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> jdbc.sql(READ).query(String.class).single())
                .get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasMessageContaining("RS001");
    }
}
