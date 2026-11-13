package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * ADR 0001 (amended 2026-11-13): Flyway applies migrations out of order, so nothing at runtime rejects a misnumbered
 * one any more. This test does: every {@code db/migration/<module>/V<n>__<module>_<what>.sql} lies inside its module's
 * block and names its module, a released migration (the manifest {@code db/released-migrations.txt}) is never
 * removed, and a migration not yet released is numbered above the highest released version of its block, so it can
 * never slide under history an installed database already has.
 */
class MigrationNumberingTest {

    /** Module folder → its version block [low, high] (ADR 0001). */
    static final Map<String, int[]> BLOCKS = Map.ofEntries(
            Map.entry("shared", new int[] {1001, 1099}),
            Map.entry("audit", new int[] {1100, 1999}),
            Map.entry("organization", new int[] {2000, 2999}),
            Map.entry("identity", new int[] {3000, 3999}),
            Map.entry("dining", new int[] {4000, 4999}),
            Map.entry("catalog", new int[] {5000, 5999}),
            Map.entry("orders", new int[] {6000, 6999}),
            Map.entry("production", new int[] {7000, 7999}),
            Map.entry("cash", new int[] {8000, 8999}),
            Map.entry("settlement", new int[] {9000, 9999}),
            Map.entry("fiscal", new int[] {10000, 10999}),
            Map.entry("customers", new int[] {11000, 11999}),
            Map.entry("promotions", new int[] {12000, 12999}),
            Map.entry("inventory", new int[] {13000, 13999}),
            Map.entry("reporting", new int[] {14000, 14999}));

    private static final Pattern FILE = Pattern.compile("V(\\d+)__([a-z]+)_.+\\.sql");
    private static final Pattern PATH = Pattern.compile(".*/db/migration/([a-z]+)/([^/]+)$");

    @Test
    void theMigrationsOnTheClasspathFollowTheBlocksAndTheReleaseManifest() throws IOException {
        Map<String, List<String>> migrations = classpathMigrations();
        assertThat(migrations).isNotEmpty();

        assertThat(violations(migrations, releasedVersions())).isEmpty();
    }

    @Test
    void aNewMigrationBelowItsBlocksReleasedHeadIsRefused() {
        assertThat(violations(Map.of("cash", List.of("V8001__cash_a.sql", "V8009__cash_b.sql", "V8005__cash_new.sql")),
                Set.of(8001, 8005, 8009)))
                .as("V8005 is released, so it is fine").isEmpty();
        assertThat(violations(Map.of("cash", List.of("V8001__cash_a.sql", "V8009__cash_b.sql", "V8008__cash_new.sql")),
                Set.of(8001, 8009)))
                .singleElement().asString().contains("V8008__cash_new.sql").contains("8009");
        assertThat(violations(Map.of("cash", List.of("V8001__cash_a.sql", "V8009__cash_b.sql", "V8010__cash_new.sql")),
                Set.of(8001, 8009)))
                .as("above the released head").isEmpty();
    }

    @Test
    void aMigrationOutsideItsBlockOrNamingAnotherModuleIsRefused() {
        assertThat(violations(Map.of("audit", List.of("V1050__audit_x.sql")), Set.of())).hasSize(1);
        assertThat(violations(Map.of("shared", List.of("V1100__shared_x.sql")), Set.of())).hasSize(1);
        assertThat(violations(Map.of("cash", List.of("V8001__settlement_x.sql")), Set.of())).hasSize(1);
        assertThat(violations(Map.of("cash", List.of("cash_x.sql")), Set.of())).hasSize(1);
        assertThat(violations(Map.of("billing", List.of("V15001__billing_x.sql")), Set.of())).hasSize(1);
    }

    @Test
    void aReleasedMigrationIsNeverRemoved() {
        assertThat(violations(Map.of("cash", List.of("V8001__cash_a.sql")), Set.of(8001, 8002)))
                .singleElement().asString().contains("8002");
    }

    static List<String> violations(Map<String, List<String>> migrationsByFolder, Set<Integer> released) {
        List<String> violations = new ArrayList<>();
        Set<Integer> present = new TreeSet<>();
        migrationsByFolder.forEach((folder, files) -> {
            int[] block = BLOCKS.get(folder);
            if (block == null) {
                violations.add(folder + ": not a module folder with a version block");
                return;
            }
            OptionalInt releasedHead = released.stream().mapToInt(Integer::intValue)
                    .filter(version -> version >= block[0] && version <= block[1]).max();
            for (String file : files) {
                Matcher name = FILE.matcher(file);
                if (!name.matches()) {
                    violations.add(folder + "/" + file + ": not V<n>__<module>_<what>.sql");
                    continue;
                }
                int version = Integer.parseInt(name.group(1));
                present.add(version);
                if (!name.group(2).equals(folder)) {
                    violations.add(folder + "/" + file + ": names module " + name.group(2));
                }
                if (version < block[0] || version > block[1]) {
                    violations.add(folder + "/" + file + ": outside the block " + block[0] + "-" + block[1]);
                }
                if (!released.contains(version) && releasedHead.isPresent() && version <= releasedHead.getAsInt()) {
                    violations.add(folder + "/" + file + ": not released, but not above the released head "
                            + releasedHead.getAsInt() + " of its block");
                }
            }
        });
        released.stream().filter(version -> !present.contains(version)).sorted()
                .forEach(version -> violations.add("V" + version + ": released but missing"));
        return violations;
    }

    /** {@code db/released-migrations.txt}: one version per line, {@code #} comments. */
    static Set<Integer> releasedVersions() throws IOException {
        try (InputStream in = new ClassPathResource("db/released-migrations.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).map(Integer::valueOf)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Map<String, List<String>> classpathMigrations() throws IOException {
        Map<String, List<String>> byFolder = new TreeMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/**/*.sql")) {
            Matcher path = PATH.matcher(resource.getURL().getPath());
            if (path.matches()) {
                byFolder.computeIfAbsent(path.group(1), folder -> new ArrayList<>()).add(path.group(2));
            } else {
                byFolder.computeIfAbsent("(root)", folder -> new ArrayList<>()).add(resource.getFilename());
            }
        }
        return byFolder;
    }
}
