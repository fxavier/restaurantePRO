package com.xavier.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * A64 (D150, D153): every metric that {@code deploy/monitoring/alert-rules.yml} evaluates must exist in a scrape of
 * the R5 backend, or a rule silently never fires. This class reads the metric names out of the rules' PromQL
 * expressions and the sample names out of a Prometheus text scrape; {@link R5AcceptanceTest} calls
 * {@link #missingFrom(String)} with its scrape of the running backend after the journey has provoked every alert, so
 * the per-store and per-kind series exist. Its own tests pin what the parser finds.
 */
class AlertRulesReferenceExistingMetricsTest {

    static final Path RULES = Path.of("..", "deploy", "monitoring", "alert-rules.yml");

    /** Series Prometheus makes itself, not the backend ({@code up} of RestaurantProBackendDown). */
    static final Set<String> SYNTHETIC = Set.of("up");

    /** PromQL words that are not metric names: operators, aggregations' modifiers. */
    static final Set<String> KEYWORDS = Set.of("and", "or", "unless", "by", "without", "on", "ignoring",
            "group_left", "group_right", "bool", "offset");

    /** An identifier not followed by "(" (that would be a function or aggregation). */
    private static final Pattern NAME = Pattern.compile("(?<![\\w:.])([a-zA-Z_:][a-zA-Z0-9_:]*)\\b(?!\\s*\\()");

    /** The metric names the rules use (D150's table), the synthetic ones left out. */
    @SuppressWarnings("unchecked")
    static Set<String> ruleMetrics() throws IOException {
        Map<String, Object> document;
        try (Reader in = Files.newBufferedReader(RULES)) {
            document = new Yaml().load(in);
        }
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) document.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                String expression = rule.get("expr").toString()
                        .replaceAll("\\{[^}]*}", " ") // label matchers
                        .replaceAll("\\[[^]]*]", " ") // range selectors
                        .replaceAll("\\b(by|without|on|ignoring)\\s*\\([^)]*\\)", " "); // grouping labels
                Matcher name = NAME.matcher(expression);
                while (name.find()) {
                    if (!KEYWORDS.contains(name.group(1))) {
                        names.add(name.group(1));
                    }
                }
            }
        }
        names.removeAll(SYNTHETIC);
        return names;
    }

    /** The sample names of a Prometheus text exposition (comments and labels left out). */
    static Set<String> sampleNames(String exposition) {
        Set<String> names = new TreeSet<>();
        exposition.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(line -> names.add(line.split("[{ ]", 2)[0]));
        return names;
    }

    /** The rules' metrics that {@code scrape} has no sample of. */
    static Set<String> missingFrom(String scrape) throws IOException {
        Set<String> missing = new TreeSet<>(ruleMetrics());
        missing.removeAll(sampleNames(scrape));
        return missing;
    }

    @Test
    void theRulesUseTheElevenMetricsOfD150() throws IOException {
        assertThat(ruleMetrics()).containsExactlyInAnyOrder("backup_status_age_seconds",
                "backup_last_success_age_seconds", "backup_problems", "filesystem_free_ratio", "print_jobs_unresolved",
                "print_jobs_oldest_waiting_seconds", "event_publications", "event_publications_oldest_incomplete_seconds",
                "payment_attempts_in_doubt_overdue", "cash_closings_with_difference", "exports_total");
    }

    @Test
    void aScrapeMissingOneRuleMetricIsReported() throws IOException {
        String scrape = """
                # HELP backup_problems Problems of the last backup check
                # TYPE backup_problems gauge
                backup_problems 0.0
                exports_total{kind="report_csv",outcome="failure"} 1.0
                """;

        assertThat(sampleNames(scrape)).containsExactlyInAnyOrder("backup_problems", "exports_total");
        assertThat(missingFrom(scrape)).contains("print_jobs_unresolved").doesNotContain("backup_problems",
                "exports_total");
    }
}
