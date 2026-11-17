package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR 0001 (amended 2026-11-13): migrations are numbered in one block per module, so a release adds versions BELOW
 * the head of an installed database (R5 adds V1105 and V3013 under R4's head V14005). Flyway must apply them out of
 * order, or validate-on-migrate rejects them as ignored and the upgrade cannot start. Each test installs a previous
 * release in its own database of the shared container, then upgrades it with the current migrations and the
 * {@code spring.flyway.out-of-order} of {@code application.yaml}:
 * <ul>
 *   <li>the last release, from the manifest {@code db/released-migrations.txt}, so every migration added since
 *       (R5's, once merged) is proven to apply on an installed database;</li>
 *   <li>a synthetic one: every real migration installed, then two test-only migrations below its head
 *       ({@code db/upgrade-test}), so the out-of-order path is proven on any branch, also before a release adds its
 *       first migration;</li>
 *   <li>the same, refused when out-of-order is off (the blocker this setting fixes).</li>
 * </ul>
 */
class FlywayUpgradeTest {

    /**
     * Migrations a release adds under the installed head: the test-only {@code db/upgrade-test} ones, which touch only
     * their own scratch objects, so no real migration that later builds on a held-back one can break the baseline.
     */
    static final Set<Integer> HELD_BACK = Set.of(901, 902);

    private static final String SCRATCH_PAGE = "upgrade_scratch.page";

    /** {@code shared.rls_protect_tenant}'s predicate as PostgreSQL prints it (RowLevelSecurityCoverageTest's). */
    private static final String CANONICAL_TENANT_PREDICATE =
            "(tenant_id = ( SELECT shared.rls_tenant_id() AS rls_tenant_id))";

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.+\\.sql");

    private final PostgreSQLContainer postgres = TestcontainersConfiguration.postgres();
    private final String database = "flyway_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcClient admin;
    private DataSource installation;

    @TempDir
    Path previousRelease;

    @TempDir
    Path currentRelease;

    @BeforeEach
    void createDatabase() {
        admin = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
        admin.sql("CREATE DATABASE " + database).update();
        installation = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName() + "?",
                "/" + database + "?"), postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void dropDatabase() {
        admin.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }

    @Test
    void theLastReleaseUpgradesToTheCurrentMigrations() throws IOException {
        Set<Integer> released = releasedVersions();
        copyMigrations((version, sql) -> released.contains(version));
        flyway(previousRelease, false).migrate();
        int installedHead = released.stream().mapToInt(Integer::intValue).max().orElseThrow();

        Flyway upgrade = flyway(currentRelease, outOfOrderOfApplicationYaml());
        upgrade.migrate();

        List<MigrationInfo> all = Arrays.asList(upgrade.info().all());
        assertThat(all).hasSize(fileCount(currentRelease));
        assertThat(all).allSatisfy(migration -> assertThat(migration.getState().isApplied())
                .as(migration.getScript() + " " + migration.getState()).isTrue());
        assertThat(all).filteredOn(migration -> !released.contains(version(migration)))
                .allSatisfy(added -> assertThat(added.getState()).as(added.getScript()).isEqualTo(
                        version(added) < installedHead ? MigrationState.OUT_OF_ORDER : MigrationState.SUCCESS));
    }

    @Test
    void migrationsBelowTheInstalledHeadAreAppliedOutOfOrder() throws IOException {
        copyMigrationsWithUpgradeTest((version, sql) -> !HELD_BACK.contains(version));
        flyway(previousRelease, false).migrate();

        Flyway upgrade = flyway(currentRelease, outOfOrderOfApplicationYaml());
        upgrade.migrate();

        List<MigrationInfo> all = Arrays.asList(upgrade.info().all());
        assertThat(all).allSatisfy(migration -> assertThat(migration.getState().isApplied())
                .as(migration.getScript() + " " + migration.getState()).isTrue());
        assertThat(all).filteredOn(migration -> HELD_BACK.contains(version(migration)))
                .extracting(MigrationInfo::getState)
                .containsExactly(MigrationState.OUT_OF_ORDER, MigrationState.OUT_OF_ORDER);
    }

    @Test
    void withoutOutOfOrderTheSameUpgradeIsRefused() throws IOException {
        copyMigrationsWithUpgradeTest((version, sql) -> !HELD_BACK.contains(version));
        flyway(previousRelease, false).migrate();

        assertThatThrownBy(() -> flyway(currentRelease, false).migrate())
                .as("the blocker this setting fixes").hasMessageContaining("ignored");
    }

    /**
     * D168 amended (V1009, V5008): a database migrated with V1008's single FOR ALL store-or-all policies (main before
     * S70) gets the per-command policies on every table the old helper protected, and the quick-page entries move to
     * the store-or-all child helper.
     */
    @Test
    void theOldStoreOrAllPoliciesAreReplacedOnAnInstalledDatabase() throws IOException {
        copyMigrations(FlywayUpgradeTest::installedBeforeS70);
        flyway(previousRelease, false).migrate();
        JdbcClient db = JdbcClient.create(installation);
        assertThat(policyNames(db, "catalog.quick_page")).containsExactly("store_or_all_scope");

        flyway(currentRelease, outOfOrderOfApplicationYaml()).migrate();

        for (String table : List.of("catalog.quick_page", "catalog.automatic_item")) {
            assertStoreOrAllPolicies(db, table);
            assertThat(db.sql("SELECT count(*) FROM pg_catalog.pg_trigger WHERE tgrelid = ?::regclass "
                    + "AND tgname = 'store_scope_fixed'").param(table).query(Long.class).single()).isOne();
        }
        assertStorePolicies(db, "catalog.quick_page_entry", "store_or_all_parent_delete",
                "store_or_all_parent_insert", "store_or_all_parent_read", "store_or_all_parent_update");
    }

    /**
     * The head of each module's block (ADR 0001: one version block per module) in the installation before S70: main
     * at f32e4b5 (the RLS-run baseline) without S70's V1009 and V5008. Fixed history: it never changes, so no later
     * migration, whatever its text, can be mistaken for a pre-S70 one. {@code from}/{@code to}: the block's versions.
     */
    private record PreS70Block(int from, int to, int head) {
    }

    private static final List<PreS70Block> PRE_S70_HEADS = List.of(
            new PreS70Block(1000, 1099, 1008),   // shared (S70 added V1009)
            new PreS70Block(1100, 1199, 1106),   // audit
            new PreS70Block(2000, 2999, 2004),   // organization
            new PreS70Block(3000, 3999, 3015),   // identity
            new PreS70Block(4000, 4999, 4007),   // dining
            new PreS70Block(5000, 5999, 5007),   // catalog (S70 added V5008)
            new PreS70Block(6000, 6999, 6030),   // orders
            new PreS70Block(7000, 7999, 7010),   // production
            new PreS70Block(8000, 8999, 8011),   // cash
            new PreS70Block(9000, 9999, 9060),   // settlement
            new PreS70Block(10000, 10999, 10012), // fiscal
            new PreS70Block(11000, 11999, 11004), // customers
            new PreS70Block(12000, 12999, 12023), // promotions
            new PreS70Block(13000, 13999, 13081), // inventory
            new PreS70Block(14000, 14999, 14006)); // reporting (tenancy, 15xxx, did not exist)

    /**
     * The installation of {@link #theOldStoreOrAllPoliciesAreReplacedOnAnInstalledDatabase}: main before S70, so every
     * version up to its block's frozen head ({@link #PRE_S70_HEADS}); S70 and all of R6 came after.
     */
    static boolean installedBeforeS70(int version, String sql) {
        return PRE_S70_HEADS.stream().anyMatch(block -> version >= block.from() && version <= block.to()
                && version <= block.head());
    }

    @Test
    void theBaselineBeforeS70LeavesOutEveryTenantMigrationAFutureSliceAdds() {
        assertThat(installedBeforeS70(6031, "ALTER TABLE orders.order_header ADD COLUMN tenant_id uuid")).isFalse();
        assertThat(installedBeforeS70(1010, "CREATE FUNCTION shared.rls_tenant_id() ... tenant_id")).isFalse();
        assertThat(installedBeforeS70(5007, "SELECT shared.rls_protect_store_or_all_table('catalog.quick_page')"))
                .isTrue();
        assertThat(installedBeforeS70(1009, "CREATE OR REPLACE FUNCTION shared.rls_protect_store_or_all_table"))
                .isFalse();
        assertThat(installedBeforeS70(1012, "SELECT shared.rls_revoke_platform_writes(table_name) "
                + "FROM shared.rls_platform_table")).isFalse();
        // S73's V2006 names neither tenant_id nor the PLATFORM registry; it still came after S70
        assertThat(installedBeforeS70(2006, "ALTER TABLE organization.terminal DROP COLUMN device_token_hash"))
                .isFalse();
        assertThat(installedBeforeS70(7012, "ALTER TABLE production.print_job ADD COLUMN claimed_by UUID"))
                .isFalse();
        assertThat(installedBeforeS70(15001, "CREATE SCHEMA tenancy")).isFalse();
        assertThat(installedBeforeS70(1106, "")).as("audit's head before S70").isTrue();
        assertThat(installedBeforeS70(1107, "")).as("audit's R6 migration").isFalse();
        assertThat(installedBeforeS70(13081, "")).isTrue();
    }

    /**
     * Once a store-or-all table's module is converted (S74d for catalog), its restrictive {@code tenant_scope} joins
     * the four store policies (D179): they stay pinned exactly, beside the canonical tenant policy.
     */
    @Test
    void aConvertedStoreOrAllTableKeepsItsFourStorePoliciesBesideItsTenantScope() throws IOException {
        JdbcClient db = currentInstallationWithScratchPage(
                "tenant_id uuid NOT NULL DEFAULT shared.rls_tenant_id(), UNIQUE (tenant_id, id)");

        assertStoreOrAllPolicies(db, SCRATCH_PAGE);
    }

    private JdbcClient currentInstallationWithScratchPage(String extraColumns) throws IOException {
        copyMigrations((version, sql) -> true);
        flyway(previousRelease, false).migrate();
        JdbcClient db = JdbcClient.create(installation);
        db.sql("CREATE SCHEMA upgrade_scratch").update();
        db.sql("CREATE TABLE " + SCRATCH_PAGE + " (id uuid PRIMARY KEY, store_id uuid, " + extraColumns + ")")
                .update();
        db.sql("SELECT shared.rls_protect_store_or_all_table(CAST(? AS regclass))").param(SCRATCH_PAGE).query()
                .listOfRows();
        return db;
    }

    /** A tenant_scope that is not the canonical restrictive one does not pass for a converted table's. */
    @Test
    void aPermissiveTenantScopeBesideTheStorePoliciesIsRefused() throws IOException {
        JdbcClient db = currentInstallationWithScratchPage("label text");
        db.sql("CREATE POLICY tenant_scope ON " + SCRATCH_PAGE + " AS PERMISSIVE FOR ALL USING (true)").update();

        assertThatThrownBy(() -> assertStoreOrAllPolicies(db, SCRATCH_PAGE)).hasMessageContaining("tenant_scope");
    }

    /**
     * The four store-or-all policies exactly, and beside them at most the canonical restrictive {@code tenant_scope}
     * of {@code shared.rls_protect_tenant} (a converted table).
     */
    private static void assertStoreOrAllPolicies(JdbcClient db, String table) {
        assertStorePolicies(db, table, "store_or_all_delete", "store_or_all_insert", "store_or_all_read",
                "store_or_all_update");
    }

    /** The store policies exactly, beside the canonical restrictive tenant_scope once the table's module converted. */
    private static void assertStorePolicies(JdbcClient db, String table, String... storePolicies) {
        assertThat(policyNames(db, table)).as(table).filteredOn(name -> !name.equals("tenant_scope"))
                .containsExactly(storePolicies);
        for (Map<String, Object> tenantScope : db.sql("""
                        SELECT polpermissive, polcmd::text AS command, pg_get_expr(polqual, polrelid) AS using_clause,
                               pg_get_expr(polwithcheck, polrelid) AS check_clause
                        FROM pg_catalog.pg_policy WHERE polrelid = CAST(? AS regclass) AND polname = 'tenant_scope'""")
                .param(table).query().listOfRows()) {
            assertThat(tenantScope).as("%s's tenant_scope is the canonical restrictive one", table)
                    .containsExactlyInAnyOrderEntriesOf(Map.of("polpermissive", false, "command", "*",
                            "using_clause", CANONICAL_TENANT_PREDICATE, "check_clause", CANONICAL_TENANT_PREDICATE));
        }
    }

    private static List<String> policyNames(JdbcClient db, String table) {
        return db.sql("SELECT polname FROM pg_catalog.pg_policy WHERE polrelid = ?::regclass ORDER BY polname")
                .param(table).query(String.class).list();
    }

    private static boolean outOfOrderOfApplicationYaml() throws IOException {
        for (PropertySource<?> yaml : new YamlPropertySourceLoader().load("application.yaml",
                new ClassPathResource("application.yaml"))) {
            Object value = yaml.getProperty("spring.flyway.out-of-order");
            if (value != null) {
                return Boolean.parseBoolean(value.toString());
            }
        }
        return false;
    }

    private Flyway flyway(Path location, boolean outOfOrder) {
        return Flyway.configure().dataSource(installation).locations("filesystem:" + location)
                .outOfOrder(outOfOrder).load();
    }

    /** Every current migration into {@link #currentRelease}; those of the previous release into the other. */
    private void copyMigrations(BiPredicate<Integer, String> inPreviousRelease) throws IOException {
        copyMigrations(inPreviousRelease, "classpath*:db/migration/**/*.sql");
    }

    /** {@link #copyMigrations} with the test-only migrations of {@code db/upgrade-test} beside the real ones. */
    private void copyMigrationsWithUpgradeTest(BiPredicate<Integer, String> inPreviousRelease) throws IOException {
        copyMigrations(inPreviousRelease, "classpath*:db/migration/**/*.sql");
        copyMigrations(inPreviousRelease, "classpath*:db/upgrade-test/*.sql");
    }

    private void copyMigrations(BiPredicate<Integer, String> inPreviousRelease, String pattern) throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources(pattern);
        assertThat(migrations).isNotEmpty();
        for (Resource migration : migrations) {
            String name = migration.getFilename();
            byte[] sql;
            try (InputStream in = migration.getInputStream()) {
                sql = in.readAllBytes();
                Files.write(currentRelease.resolve(name), sql);
            }
            if (inPreviousRelease.test(version(name), new String(sql, StandardCharsets.UTF_8))) {
                Files.copy(currentRelease.resolve(name), previousRelease.resolve(name));
            }
        }
    }

    private static Set<Integer> releasedVersions() throws IOException {
        try (InputStream in = new ClassPathResource("db/released-migrations.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).map(Integer::valueOf)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static int version(MigrationInfo migration) {
        return Integer.parseInt(migration.getVersion().getVersion());
    }

    private static int version(String file) {
        Matcher name = VERSION.matcher(file);
        if (!name.matches()) {
            throw new IllegalStateException("not a versioned migration: " + file);
        }
        return Integer.parseInt(name.group(1));
    }

    private static int fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return (int) files.count();
        }
    }
}
