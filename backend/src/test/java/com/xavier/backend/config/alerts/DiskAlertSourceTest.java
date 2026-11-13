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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D150 DISK_SPACE_LOW: a filesystem of the status file ({@code backups}, {@code docker}) or the application's own with
 * less than 15 % free is a WARNING, less than 5 % CRITICAL. One installation-wide alert at the worst severity, counting
 * the low filesystems.
 */
class DiskAlertSourceTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @TempDir
    Path dir;

    final TestClock clock = new TestClock(NOW);

    @Test
    void fifteenPercentFreeOrMoreIsFine() throws IOException {
        assertThat(alerts(150, 1000, new Filesystem("app", 100, 15))).isEmpty();
    }

    @Test
    void lessThanFifteenPercentFreeIsAWarning() throws IOException {
        assertThat(alerts(149, 1000, new Filesystem("app", 100, 50)))
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::storeId,
                        OperationalAlert::count, OperationalAlert::summary)
                .containsExactly(tuple("DISK_SPACE_LOW", AlertSeverity.WARNING, null, 1L,
                        "Disk space low: backups 14% free."));
    }

    @Test
    void fivePercentFreeIsStillOnlyAWarning() throws IOException {
        assertThat(alerts(50, 1000, new Filesystem("app", 100, 50)))
                .extracting(OperationalAlert::severity).containsExactly(AlertSeverity.WARNING);
    }

    @Test
    void lessThanFivePercentFreeIsCriticalAndTheWorstSeverityWins() throws IOException {
        assertThat(alerts(49, 1000, new Filesystem("app", 100, 10)))
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::count,
                        OperationalAlert::summary)
                .containsExactly(tuple("DISK_SPACE_LOW", AlertSeverity.CRITICAL, 2L,
                        "Disk space low: backups 4% free, app 10% free."));
    }

    @Test
    void theApplicationsFilesystemIsCheckedWithoutAStatusFile() {
        BackupStatusReader reader = new BackupStatusReader(null, () -> Optional.of(new Filesystem("app", 100, 4)),
                new SimpleMeterRegistry(), clock);

        assertThat(new DiskAlertSource(reader, clock).alerts())
                .extracting(OperationalAlert::severity, OperationalAlert::summary)
                .containsExactly(tuple(AlertSeverity.CRITICAL, "Disk space low: app 4% free."));
    }

    @Test
    void aFilesystemOfUnknownSizeIsNeverLow() {
        BackupStatusReader reader = new BackupStatusReader(null, () -> Optional.of(new Filesystem("app", 0, 0)),
                new SimpleMeterRegistry(), clock);

        assertThat(new DiskAlertSource(reader, clock).alerts()).isEmpty();
    }

    private List<OperationalAlert> alerts(long backupsFree, long backupsTotal, Filesystem app) throws IOException {
        Path file = Files.createTempFile(dir, "backup-status", ".json");
        Files.writeString(file, """
                {"schemaVersion": 1, "checkedAt": "%s", "ok": true, "lastLogicalBackupAt": "%1$s",
                 "lastBaseBackupAt": "%1$s", "lastOffboxSyncAt": "%1$s", "problems": [],
                 "filesystems": [{"name": "backups", "totalBytes": %d, "freeBytes": %d},
                                 {"name": "docker", "totalBytes": 1000, "freeBytes": 900}]}"""
                .formatted(NOW, backupsTotal, backupsFree));
        BackupStatusReader reader = new BackupStatusReader(file, () -> Optional.of(app), new SimpleMeterRegistry(),
                clock);
        return new DiskAlertSource(reader, clock).alerts();
    }
}
