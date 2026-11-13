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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
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
 *   <li>a synthetic one holding back the newest migration of two low blocks, so the out-of-order path is proven on
 *       any branch, also before a release adds its first migration;</li>
 *   <li>the same, refused when out-of-order is off (the blocker this setting fixes).</li>
 * </ul>
 */
class FlywayUpgradeTest {

    /** Newer migrations of low blocks, as a release adds them under an installed head of a higher block. */
    static final Set<Integer> HELD_BACK = Set.of(1006, 8009);

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
        copyMigrations(released::contains);
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
        copyMigrations(version -> !HELD_BACK.contains(version));
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
        copyMigrations(version -> !HELD_BACK.contains(version));
        flyway(previousRelease, false).migrate();

        assertThatThrownBy(() -> flyway(currentRelease, false).migrate())
                .as("the blocker this setting fixes").hasMessageContaining("ignored");
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
    private void copyMigrations(Predicate<Integer> inPreviousRelease) throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/**/*.sql");
        assertThat(migrations).isNotEmpty();
        for (Resource migration : migrations) {
            String name = migration.getFilename();
            try (InputStream in = migration.getInputStream()) {
                Files.write(currentRelease.resolve(name), in.readAllBytes());
            }
            if (inPreviousRelease.test(version(name))) {
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
