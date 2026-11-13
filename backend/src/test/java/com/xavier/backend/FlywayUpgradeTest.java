package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR 0001 (amended 2026-11-13): migrations are numbered in one block per module, so a release adds versions BELOW
 * the head of an installed database (R5 adds V1105 and V3013 to an R4 database whose head is V14005). Flyway must
 * apply them out of order, or validate-on-migrate rejects them as ignored and the upgrade cannot start. This test
 * installs a "previous release" (every migration except the newest of two low blocks, so the head stays the highest
 * block), then upgrades it with the full set, using the {@code spring.flyway.out-of-order} of
 * {@code application.yaml}. It runs on its own PostgreSQL, so the upgrade starts from a database of that release.
 */
class FlywayUpgradeTest {

    /** Newer migrations of low blocks, as a release adds them under an installed head of a higher block. */
    static final Set<String> ADDED_BY_THE_UPGRADE = Set.of("V1006__shared_event_publication_pending_ix.sql",
            "V8009__cash_day_reopen_from_status_and_closing_round.sql");

    @TempDir
    Path previousRelease;

    @TempDir
    Path currentRelease;

    @Test
    void anInstalledDatabaseUpgradesWithMigrationsBelowItsHead() throws IOException {
        copyMigrations();
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
                .withUrlParam("sslmode", "disable")) {
            postgres.start();
            flyway(postgres, previousRelease, false).migrate();

            Flyway upgrade = flyway(postgres, currentRelease, outOfOrderOfApplicationYaml());
            upgrade.migrate();

            List<MigrationInfo> all = Arrays.asList(upgrade.info().all());
            assertThat(all).hasSize(fileCount(currentRelease));
            assertThat(all).allSatisfy(migration -> assertThat(migration.getState().isApplied())
                    .as(migration.getScript() + " " + migration.getState()).isTrue());
            assertThat(all).filteredOn(migration -> ADDED_BY_THE_UPGRADE.contains(migration.getScript()))
                    .extracting(MigrationInfo::getState).containsOnly(MigrationState.OUT_OF_ORDER);
        }
    }

    @Test
    void withoutOutOfOrderTheSameUpgradeIsRefused() throws IOException {
        copyMigrations();
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
                .withUrlParam("sslmode", "disable")) {
            postgres.start();
            flyway(postgres, previousRelease, false).migrate();

            assertThatThrownBy(() -> flyway(postgres, currentRelease, false).migrate())
                    .as("the blocker this setting fixes").hasMessageContaining("ignored");
        }
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

    private static Flyway flyway(PostgreSQLContainer postgres, Path location, boolean outOfOrder) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + location)
                .outOfOrder(outOfOrder)
                .load();
    }

    /** The current migrations into {@link #currentRelease}; all but {@link #ADDED_BY_THE_UPGRADE} into the other. */
    private void copyMigrations() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/**/*.sql");
        assertThat(migrations).isNotEmpty();
        for (Resource migration : migrations) {
            String name = migration.getFilename();
            try (InputStream in = migration.getInputStream()) {
                Files.write(currentRelease.resolve(name), in.readAllBytes());
            }
            if (!ADDED_BY_THE_UPGRADE.contains(name)) {
                Files.copy(currentRelease.resolve(name), previousRelease.resolve(name));
            }
        }
        assertThat(ADDED_BY_THE_UPGRADE).allMatch(name -> Files.exists(currentRelease.resolve(name)));
    }

    private static int fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return (int) files.count();
        }
    }
}
