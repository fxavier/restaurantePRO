package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.xavier.backend.config.alerts.BackupStatus.Filesystem;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * D150/D151: the backup status file written by {@code verify-backups.sh} (schema 1), read at most once a minute, and
 * the {@code BACKUP_*} alerts and gauges derived from it. A missing, malformed or unknown-schema file is "no status":
 * the monitor itself is stale.
 */
@ExtendWith(OutputCaptureExtension.class)
class BackupStatusReaderTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @TempDir
    Path dir;

    final TestClock clock = new TestClock(NOW);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final Filesystem app = new Filesystem("app", 1000, 900);

    @Test
    void aFreshCheckWithRecentBackupsRaisesNoAlertAndReportsTheAges() throws IOException {
        BackupStatusReader reader = reader(write(status(NOW.minusSeconds(600), true, NOW.minusSeconds(7200),
                NOW.minusSeconds(10800), NOW.minusSeconds(300), "[]")));

        assertThat(alerts(reader)).isEmpty();
        assertThat(gauge("backup_status_age_seconds")).isEqualTo(600);
        assertThat(meters.get("backup_last_success_age_seconds").tag("kind", "logical").gauge().value())
                .isEqualTo(7200);
        assertThat(meters.get("backup_last_success_age_seconds").tag("kind", "base").gauge().value())
                .isEqualTo(10800);
        assertThat(meters.get("backup_last_success_age_seconds").tag("kind", "offbox").gauge().value())
                .isEqualTo(300);
        assertThat(gauge("backup_problems")).isZero();
        assertThat(meters.get("filesystem_free_ratio").tag("name", "backups").gauge().value()).isEqualTo(0.25);
        assertThat(meters.get("filesystem_free_ratio").tag("name", "docker").gauge().value()).isEqualTo(0.5);
        assertThat(meters.get("filesystem_free_ratio").tag("name", "app").gauge().value()).isEqualTo(0.9);
    }

    @Test
    void aCheckOlderThan45MinutesMakesTheMonitorStale() throws IOException {
        Instant checkedAt = NOW.minus(Duration.ofMinutes(45));
        BackupStatusReader reader = reader(write(status(checkedAt, true, NOW, NOW, NOW, "[]")));

        assertThat(alerts(reader)).as("exactly 45 min is not older than 45 min").isEmpty();

        clock.advance(Duration.ofSeconds(1));

        assertThat(alerts(reader)).extracting(OperationalAlert::code, OperationalAlert::severity,
                        OperationalAlert::storeId, OperationalAlert::since)
                .containsExactly(tuple("BACKUP_MONITOR_STALE", AlertSeverity.CRITICAL, null, checkedAt));
    }

    @Test
    void aMissingFileMakesTheMonitorStaleAndItsAgeInfinite() {
        BackupStatusReader reader = reader(dir.resolve("absent.json"));

        assertThat(alerts(reader)).extracting(OperationalAlert::code, OperationalAlert::severity,
                        OperationalAlert::since)
                .containsExactly(tuple("BACKUP_MONITOR_STALE", AlertSeverity.CRITICAL, NOW));
        assertThat(gauge("backup_status_age_seconds")).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(gauge("backup_problems")).isNaN();
        assertThat(meters.get("filesystem_free_ratio").tag("name", "backups").gauge().value()).isNaN();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            "{\"schemaVersion\": 2, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": [],"
                    + " \"filesystems\": []}",
            "{\"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": [], \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"ok\": true, \"problems\": [], \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"yesterday\", \"ok\": true, \"problems\": [],"
                    + " \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"problems\": [], \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": \"none\","
                    + " \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": [],"
                    + " \"lastBaseBackupAt\": 12, \"filesystems\": []}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": [],"
                    + " \"filesystems\": [{\"name\": \"backups\", \"totalBytes\": \"x\", \"freeBytes\": 1}]}",
            "{\"schemaVersion\": 1, \"checkedAt\": \"2026-11-12T09:59:00Z\", \"ok\": true, \"problems\": [],"
                    + " \"filesystems\": [{\"name\": \"backups\", \"totalBytes\": 10, \"freeBytes\": 11}]}",
    })
    void aMalformedOrUnknownSchemaFileIsTreatedAsMissing(String content) throws IOException {
        BackupStatusReader reader = reader(write(content));

        assertThat(alerts(reader)).extracting(OperationalAlert::code).containsExactly("BACKUP_MONITOR_STALE");
        assertThat(gauge("backup_status_age_seconds")).as("so the Prometheus rule > 2700 fires")
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void anUnusableFileIsWarnedOnceUntilItIsValidAgain(CapturedOutput output) throws IOException {
        Path file = write("not json");
        BackupStatusReader reader = reader(file);

        alerts(reader);
        clock.advance(Duration.ofMinutes(1));
        alerts(reader);
        assertThat(warnings(output)).hasSize(1);

        Files.writeString(file, fresh(NOW));
        clock.advance(Duration.ofMinutes(1));
        assertThat(alerts(reader)).isEmpty();
        Files.writeString(file, "still not json");
        clock.advance(Duration.ofMinutes(1));
        alerts(reader);
        assertThat(warnings(output)).hasSize(2);
    }

    @Test
    void theFileIsReadAtMostOnceAMinute() throws IOException {
        Path file = write(fresh(NOW));
        BackupStatusReader reader = reader(file);
        assertThat(alerts(reader)).isEmpty();

        Files.writeString(file, "not json");
        clock.advance(Duration.ofSeconds(59));
        assertThat(alerts(reader)).as("the last read is still current").isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(alerts(reader)).extracting(OperationalAlert::code).containsExactly("BACKUP_MONITOR_STALE");
    }

    @Test
    void aLogicalOrBaseBackupOlderThan26HoursOrAnOffboxCopyOlderThan30MinutesIsLate() throws IOException {
        Instant onTime = NOW.minus(Duration.ofHours(26));
        BackupStatusReader reader = reader(write(status(NOW, true, onTime, onTime,
                NOW.minus(Duration.ofMinutes(30)), "[]")));
        assertThat(alerts(reader)).as("exactly at the limits").isEmpty();

        Instant late = onTime.minusSeconds(1);
        assertThat(lateWith(late, NOW, NOW)).extracting(OperationalAlert::code, OperationalAlert::count,
                OperationalAlert::since).containsExactly(tuple("BACKUP_LATE", 1L, late));
        assertThat(lateWith(NOW, late, NOW)).extracting(OperationalAlert::code).containsExactly("BACKUP_LATE");
        Instant offboxLate = NOW.minus(Duration.ofMinutes(30)).minusSeconds(1);
        assertThat(lateWith(NOW, NOW, offboxLate)).extracting(OperationalAlert::code, OperationalAlert::severity,
                OperationalAlert::summary).containsExactly(tuple("BACKUP_LATE", AlertSeverity.CRITICAL,
                "Backups are late: offbox."));
    }

    @Test
    void aBackupNeverTakenIsLateAndItsAgeInfinite() throws IOException {
        BackupStatusReader reader = reader(write(status(NOW, true, NOW, null, NOW, "[]")));

        assertThat(alerts(reader)).extracting(OperationalAlert::code, OperationalAlert::count,
                OperationalAlert::summary).containsExactly(tuple("BACKUP_LATE", 1L, "Backups are late: base."));
        assertThat(meters.get("backup_last_success_age_seconds").tag("kind", "base").gauge().value())
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void theProblemsOfTheLastCheckAreAnAlertWithoutTheirText() throws IOException {
        BackupStatusReader reader = reader(write(status(NOW, false, NOW, NOW, NOW,
                "[\"the archiver failed\", \"no base backup\"]")));

        assertThat(alerts(reader)).extracting(OperationalAlert::code, OperationalAlert::severity,
                        OperationalAlert::count, OperationalAlert::since)
                .containsExactly(tuple("BACKUP_PROBLEM", AlertSeverity.CRITICAL, 2L, NOW));
        assertThat(alerts(reader).getFirst().summary()).doesNotContain("archiver");
        assertThat(gauge("backup_problems")).isEqualTo(2);
    }

    @Test
    void aCheckThatIsNotOkWithoutProblemsIsStillAProblem() throws IOException {
        BackupStatusReader reader = reader(write(status(NOW, false, NOW, NOW, NOW, "[]")));

        assertThat(alerts(reader)).extracting(OperationalAlert::code, OperationalAlert::count)
                .containsExactly(tuple("BACKUP_PROBLEM", 1L));
        assertThat(gauge("backup_problems")).isEqualTo(1);
    }

    @Test
    void withoutAConfiguredFileThereAreNoBackupAlertsOrGaugesButTheAppDiskIsMeasured() {
        BackupStatusReader reader = new BackupStatusReader(null, () -> Optional.of(app), meters, clock);

        assertThat(alerts(reader)).isEmpty();
        assertThat(meters.find("backup_status_age_seconds").gauge()).isNull();
        assertThat(meters.find("backup_problems").gauge()).isNull();
        assertThat(meters.find("filesystem_free_ratio").tag("name", "backups").gauge()).isNull();
        assertThat(reader.filesystems()).containsExactly(app);
    }

    @Test
    void theFilesystemsAreThoseOfTheFileThenTheApplications() throws IOException {
        BackupStatusReader reader = reader(write(fresh(NOW)));

        assertThat(reader.filesystems()).containsExactly(new Filesystem("backups", 400, 100),
                new Filesystem("docker", 1000, 500), app);
    }

    private List<OperationalAlert> lateWith(Instant logical, Instant base, Instant offbox) throws IOException {
        SimpleMeterRegistry otherMeters = new SimpleMeterRegistry();
        BackupStatusReader reader = new BackupStatusReader(write(status(NOW, true, logical, base, offbox, "[]")),
                () -> Optional.of(app), otherMeters, clock);
        return new BackupAlertSource(reader, clock).alerts();
    }

    private BackupStatusReader reader(Path file) {
        return new BackupStatusReader(file, () -> Optional.of(app), meters, clock);
    }

    private List<OperationalAlert> alerts(BackupStatusReader reader) {
        return new BackupAlertSource(reader, clock).alerts();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private static List<String> warnings(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("WARN")
                && line.contains("backup status file")).toList();
    }

    private Path write(String content) throws IOException {
        Path file = Files.createTempFile(dir, "backup-status", ".json");
        Files.writeString(file, content);
        return file;
    }

    private static String fresh(Instant checkedAt) {
        return status(checkedAt, true, checkedAt, checkedAt, checkedAt, "[]");
    }

    private static String status(Instant checkedAt, boolean ok, Instant logical, Instant base, Instant offbox,
                                 String problems) {
        return """
                {"schemaVersion": 1, "checkedAt": "%s", "ok": %s,
                 "lastLogicalBackupAt": %s, "lastBaseBackupAt": %s, "lastOffboxSyncAt": %s,
                 "problems": %s,
                 "filesystems": [{"name": "backups", "totalBytes": 400, "freeBytes": 100},
                                 {"name": "docker", "totalBytes": 1000, "freeBytes": 500}]}"""
                .formatted(checkedAt, ok, quoted(logical), quoted(base), quoted(offbox), problems);
    }

    private static String quoted(Instant instant) {
        return instant == null ? "null" : "\"" + instant + "\"";
    }
}
