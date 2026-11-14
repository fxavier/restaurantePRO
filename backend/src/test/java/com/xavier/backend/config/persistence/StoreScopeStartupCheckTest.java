package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.audit.AuditLog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * D163 startup check (A69 backend part, A75 "dropping a policy makes the startup check refuse to start"). Each case
 * runs in one superuser transaction that is rolled back, impersonating the pool's role with {@code SET LOCAL ROLE}, so
 * the roles and tables it creates never outlive it.
 */
class StoreScopeStartupCheckTest {

    @Test
    void theApplicationRoleOnTheMigratedDatabasePasses() throws SQLException {
        assertThat(asRole(TestcontainersConfiguration.APP_ROLE, StoreScopeStartupCheck::problems)).isEmpty();
    }

    @Test
    void aSuperuserIsRefused() throws SQLException {
        assertThat(inRolledBackTransaction(StoreScopeStartupCheck::problems))
                .anySatisfy(problem -> assertThat(problem).contains("superuser"));
    }

    @Test
    void theOwnerIsRefusedForBypassingRlsAndOwningObjects() throws SQLException {
        assertThat(asRole(TestcontainersConfiguration.OWNER_ROLE, StoreScopeStartupCheck::problems))
                .anySatisfy(problem -> assertThat(problem).contains("BYPASSRLS"))
                .anySatisfy(problem -> assertThat(problem).contains("owns"));
    }

    @Test
    void aBypassRlsRoleIsRefused() throws SQLException {
        List<String> problems = inRolledBackTransaction(jdbc -> {
            jdbc.sql("CREATE ROLE rls_check_bypass NOLOGIN BYPASSRLS").update();
            jdbc.sql("SET LOCAL ROLE rls_check_bypass").update();
            return StoreScopeStartupCheck.problems(jdbc);
        });

        assertThat(problems).first().asString().contains("BYPASSRLS");
        assertThat(problems).noneSatisfy(problem -> assertThat(problem).contains("owns"));
    }

    @Test
    void aRoleOwningAnApplicationObjectIsRefused() throws SQLException {
        List<String> problems = inRolledBackTransaction(jdbc -> {
            jdbc.sql("CREATE ROLE rls_check_owner NOLOGIN").update();
            jdbc.sql("CREATE SCHEMA rls_check_owned AUTHORIZATION rls_check_owner").update();
            jdbc.sql("SET LOCAL ROLE rls_check_owner").update();
            return StoreScopeStartupCheck.problems(jdbc);
        });

        assertThat(problems).first().asString().contains("owns 1 application object");
    }

    @Test
    void aStoreTableThatLostItsPolicyOrItsForceIsRefused() throws SQLException {
        List<String> problems = inRolledBackTransaction(jdbc -> {
            jdbc.sql("CREATE SCHEMA rls_check_lost").update();
            for (String table : List.of("dropped", "unforced", "kept")) {
                jdbc.sql("CREATE TABLE rls_check_lost." + table + " (id uuid PRIMARY KEY, store_id uuid NOT NULL)")
                        .update();
                jdbc.sql("SELECT shared.rls_protect_store_table('rls_check_lost." + table + "')").query().listOfRows();
            }
            jdbc.sql("CREATE TABLE rls_check_lost.never (id uuid PRIMARY KEY, to_store_id uuid)").update();
            jdbc.sql("DROP POLICY store_scope ON rls_check_lost.dropped").update();
            jdbc.sql("ALTER TABLE rls_check_lost.unforced NO FORCE ROW LEVEL SECURITY").update();
            jdbc.sql("SET LOCAL ROLE " + TestcontainersConfiguration.APP_ROLE).update();
            return StoreScopeStartupCheck.problems(jdbc);
        });

        assertThat(problems).singleElement().asString()
                .contains("rls_check_lost.dropped", "rls_check_lost.unforced", "rls_check_lost.never")
                .doesNotContain("rls_check_lost.kept");
    }

    @Test
    void aSchemaWhoseOnlyProtectedTablesAllLostTheirProtectionIsRefused() throws SQLException {
        List<String> problems = inRolledBackTransaction(jdbc -> {
            jdbc.sql("CREATE SCHEMA rls_check_whole").update();
            for (String table : List.of("disabled", "unforced", "policyless")) {
                jdbc.sql("CREATE TABLE rls_check_whole." + table + " (id uuid PRIMARY KEY, store_id uuid NOT NULL)")
                        .update();
                jdbc.sql("SELECT shared.rls_protect_store_table('rls_check_whole." + table + "')").query()
                        .listOfRows();
            }
            // a restore without policies, or a migration gone wrong: nothing in the schema is protected any more
            jdbc.sql("ALTER TABLE rls_check_whole.disabled DISABLE ROW LEVEL SECURITY").update();
            jdbc.sql("ALTER TABLE rls_check_whole.unforced NO FORCE ROW LEVEL SECURITY").update();
            jdbc.sql("DROP POLICY store_scope ON rls_check_whole.policyless").update();
            jdbc.sql("ALTER TABLE rls_check_whole.policyless DISABLE ROW LEVEL SECURITY").update();
            jdbc.sql("ALTER TABLE rls_check_whole.unforced DISABLE ROW LEVEL SECURITY").update();
            jdbc.sql("SET LOCAL ROLE " + TestcontainersConfiguration.APP_ROLE).update();
            return StoreScopeStartupCheck.problems(jdbc);
        });

        assertThat(problems).singleElement().asString().contains("rls_check_whole.disabled",
                "rls_check_whole.unforced", "rls_check_whole.policyless");
    }

    @Test
    void aSpecialTableRegisteredAsProtectedThatLostItsPolicyIsRefused() throws SQLException {
        List<String> problems = inRolledBackTransaction(jdbc -> {
            jdbc.sql("CREATE SCHEMA rls_check_special").update();
            jdbc.sql("CREATE TABLE rls_check_special.journal (id uuid PRIMARY KEY, store_id uuid)").update();
            jdbc.sql("ALTER TABLE rls_check_special.journal ENABLE ROW LEVEL SECURITY").update();
            jdbc.sql("ALTER TABLE rls_check_special.journal FORCE ROW LEVEL SECURITY").update();
            jdbc.sql("CREATE POLICY journal_read ON rls_check_special.journal FOR SELECT USING (true)").update();
            jdbc.sql("SELECT shared.rls_register_protected('rls_check_special.journal')").query().listOfRows();
            jdbc.sql("DROP POLICY journal_read ON rls_check_special.journal").update();
            jdbc.sql("SET LOCAL ROLE " + TestcontainersConfiguration.APP_ROLE).update();
            return StoreScopeStartupCheck.problems(jdbc);
        });

        assertThat(problems).singleElement().asString().contains("rls_check_special.journal");
    }

    @Test
    void theBackendRefusesToStartAsTheSuperuser() throws SQLException {
        try (Connection connection = superuser()) {
            StoreScopeStartupCheck check = new StoreScopeStartupCheck(new SingleConnectionDataSource(connection, true),
                    new MockEnvironment(), mock(AuditLog.class), null);

            assertThatThrownBy(check::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refuses to start").hasMessageContaining("superuser");
        }
    }

    @Test
    void permissiveModeStartsAndIsAuditedOnce() throws Exception {
        try (Connection connection = application()) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            AuditLog audit = mock(AuditLog.class);
            new StoreScopeStartupCheck(dataSource, new MockEnvironment()
                    .withProperty(StoreScopeEnforcement.PROPERTY, "PERMISSIVE"), audit,
                    new DataSourceTransactionManager(dataSource)).afterPropertiesSet();
            AuditLog enforcing = mock(AuditLog.class);
            new StoreScopeStartupCheck(dataSource, new MockEnvironment(), enforcing,
                    new DataSourceTransactionManager(dataSource)).afterPropertiesSet();

            verify(audit).recordAsSystem(eq(StoreScopeStartupCheck.PERMISSIVE_STARTED), eq("Installation"),
                    eq("store-scope"), isNull(), isNull(), any(), any());
            verify(enforcing, never()).recordAsSystem(any(), any(), any(), any(), any(), any(), any());
        }
    }

    private static List<String> asRole(String role, Function<JdbcClient, List<String>> check) throws SQLException {
        return inRolledBackTransaction(jdbc -> {
            jdbc.sql("SET LOCAL ROLE " + role).update();
            return check.apply(jdbc);
        });
    }

    private static <T> T inRolledBackTransaction(Function<JdbcClient, T> work) throws SQLException {
        try (Connection connection = superuser()) {
            connection.setAutoCommit(false);
            try {
                return work.apply(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            } finally {
                connection.rollback();
            }
        }
    }

    private static Connection superuser() throws SQLException {
        var postgres = TestcontainersConfiguration.postgres();
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection application() throws SQLException {
        return DriverManager.getConnection(TestcontainersConfiguration.postgres().getJdbcUrl(),
                TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword());
    }
}
