package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.DatabaseRoles;
import com.xavier.backend.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PLATFORM tables (D179) are shared by every tenant and have no tenant policy, so the pool must not write them: one
 * tenant would change them for all. {@code deploy/postgres/provision-roles.sql} grants data privileges on every table,
 * and the owner's default privileges do the same for every table a migration creates; {@code
 * shared.rls_revoke_platform_writes} (called when a table is registered PLATFORM, and by provisioning at every start)
 * takes the writes back from each PLATFORM table outside its allow-list. Checked on a database of its own, as an
 * installation lives: provisioned, migrated by the owner (first install), provisioned again (every start).
 */
class PlatformTablePrivilegesTest {

    /** The PLATFORM tables the application writes: the job leases (D185) and Spring Modulith's event registry. */
    static final List<String> WRITTEN_BY_THE_APPLICATION = List.of("public.event_publication",
            "public.event_publication_archive", "shared.job_lease");

    private static final PostgreSQLContainer POSTGRES = TestcontainersConfiguration.postgres();
    private static final String DATABASE = "platform_privileges_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SCRIPT_IN_CONTAINER = "/tmp/platform-privileges-provision-roles.sql";

    private static JdbcClient admin;
    private static JdbcClient installation;
    private static List<String> writableAfterTheFirstInstall;

    @BeforeAll
    static void provisionMigrateAndProvisionAgain() throws Exception {
        admin = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.sql("CREATE DATABASE " + DATABASE).update();
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName() + "?", "/" + DATABASE + "?");
        installation = JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(provisioningScript()), SCRIPT_IN_CONTAINER);

        provision();
        Flyway.configure().dataSource(url, DatabaseRoles.OWNER_ROLE, DatabaseRoles.ownerPassword())
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        writableAfterTheFirstInstall = platformTablesThePoolWrites();
        provision();
    }

    @AfterAll
    static void dropDatabase() {
        admin.sql("DROP DATABASE " + DATABASE + " WITH (FORCE)").update();
    }

    /** The migrations take the writes back themselves: a first install is safe before any restart. */
    @Test
    void aFirstInstallLeavesThePoolNoWriteOnAPlatformTableOutsideTheAllowList() {
        assertThat(writableAfterTheFirstInstall).isEmpty();
    }

    @Test
    void afterProvisioningThePoolWritesNoPlatformTableOutsideTheAllowList() {
        assertThat(platformTablesThePoolWrites()).isEmpty();
    }

    private static List<String> platformTablesThePoolWrites() {
        return installation.sql("""
                        SELECT t.name || ' ' || w.privilege
                        FROM (SELECT p.table_name, n.nspname || '.' || c.relname AS name
                              FROM shared.rls_platform_table p
                                  JOIN pg_catalog.pg_class c ON c.oid = p.table_name
                                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace) t,
                            unnest(ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) AS w(privilege)
                        WHERE has_table_privilege(?, t.table_name, w.privilege) AND t.name <> ALL (?)
                        ORDER BY 1""")
                .param(DatabaseRoles.APP_ROLE).param(WRITTEN_BY_THE_APPLICATION.toArray(String[]::new))
                .query(String.class).list();
    }

    @Test
    void afterProvisioningThePoolStillWritesTheAllowListedPlatformTables() {
        assertThat(installation.sql("""
                        SELECT t.name || ' ' || w.privilege FROM unnest(?) AS t(name),
                            unnest(ARRAY['INSERT', 'UPDATE', 'DELETE']) AS w(privilege)
                        WHERE has_table_privilege(?, t.name, w.privilege)""")
                .param(WRITTEN_BY_THE_APPLICATION.toArray(String[]::new)).param(DatabaseRoles.APP_ROLE)
                .query(String.class).list()).hasSize(WRITTEN_BY_THE_APPLICATION.size() * 3);
    }

    /** Provisioning runs them as a superuser: an object planted earlier on its search_path must not be resolved. */
    @Test
    void thePlatformWriteFunctionsPinTheirSearchPath() {
        assertThat(installation.sql("""
                        SELECT p.proname || ' ' || coalesce(array_to_string(p.proconfig, ','), '')
                        FROM pg_catalog.pg_proc p
                        WHERE p.pronamespace = 'shared'::regnamespace
                          AND p.proname IN ('rls_platform_writable', 'rls_revoke_platform_writes')
                        ORDER BY 1""")
                .query(String.class).list())
                .containsExactly("rls_platform_writable search_path=pg_catalog",
                        "rls_revoke_platform_writes search_path=pg_catalog");
    }

    /** {@code provision-roles.sql} on this test's database, as {@code DatabaseRoles#provision} runs it on the shared one. */
    private static void provision() throws Exception {
        ExecResult result = POSTGRES.execInContainer("psql", "-X", "-q", "-U", POSTGRES.getUsername(), "-d", DATABASE,
                "-v", "ON_ERROR_STOP=1", "-v", "owner_password=" + DatabaseRoles.ownerPassword(), "-v",
                "app_password=" + DatabaseRoles.appPassword(), "-f", SCRIPT_IN_CONTAINER);
        assertThat(result.getExitCode()).as("provision-roles.sql on %s: %s", DATABASE, result.getStderr()).isZero();
    }

    private static Path provisioningScript() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy/postgres/provision-roles.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("deploy/postgres/provision-roles.sql not found");
    }
}
