package com.xavier.backend.config.alerts;

import com.xavier.backend.config.alerts.BackupStatusReader.Reading;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The backup alerts of D150, installation-wide and CRITICAL, from the status file: {@code BACKUP_MONITOR_STALE} (no
 * usable file, or a check older than 45 min), {@code BACKUP_LATE} (a logical or base backup older than 26 h, or an
 * off-box copy older than 30 min; never taken counts as late) and {@code BACKUP_PROBLEM} (the last check was not ok).
 * Nothing without a configured file.
 */
@Component
class BackupAlertSource implements AlertSource {

    static final Duration STALE_AFTER = Duration.ofMinutes(45);
    static final Duration BACKUP_MAX_AGE = Duration.ofHours(26);
    static final Duration OFFBOX_MAX_AGE = Duration.ofMinutes(30);

    private final BackupStatusReader reader;
    private final Clock clock;

    BackupAlertSource(BackupStatusReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public List<OperationalAlert> alerts() {
        if (!reader.configured()) {
            return List.of();
        }
        Reading reading = reader.read();
        BackupStatus status = reading.status();
        if (status == null) {
            return List.of(alert("BACKUP_MONITOR_STALE", 1, reading.unusableSince(),
                    "The backup status file is missing or unreadable: is verify-backups.sh running?"));
        }
        Instant now = clock.instant();
        List<OperationalAlert> alerts = new ArrayList<>();
        if (olderThan(status.checkedAt(), STALE_AFTER, now)) {
            alerts.add(alert("BACKUP_MONITOR_STALE", 1, status.checkedAt(),
                    "The last backup check is older than 45 min: is verify-backups.sh running?"));
        }
        Map<String, Instant> late = late(status, now);
        if (!late.isEmpty()) {
            Instant since = late.values().stream().filter(Objects::nonNull).min(Comparator.naturalOrder())
                    .orElse(status.checkedAt());
            alerts.add(alert("BACKUP_LATE", late.size(), since,
                    "Backups are late: " + String.join(", ", late.keySet()) + "."));
        }
        if (status.problemCount() > 0) {
            alerts.add(alert("BACKUP_PROBLEM", status.problemCount(), status.checkedAt(),
                    "The last backup check reported " + status.problemCount() + " problem(s); see verify-backups.sh."));
        }
        return alerts;
    }

    /** The late kinds, in a fixed order, each with its last success ({@code null} = never). */
    private static Map<String, Instant> late(BackupStatus status, Instant now) {
        Map<String, Instant> late = new LinkedHashMap<>();
        if (olderThan(status.lastLogicalBackupAt(), BACKUP_MAX_AGE, now)) {
            late.put("logical", status.lastLogicalBackupAt());
        }
        if (olderThan(status.lastBaseBackupAt(), BACKUP_MAX_AGE, now)) {
            late.put("base", status.lastBaseBackupAt());
        }
        if (olderThan(status.lastOffboxSyncAt(), OFFBOX_MAX_AGE, now)) {
            late.put("offbox", status.lastOffboxSyncAt());
        }
        return late;
    }

    /** {@code null} (never) is older than anything. */
    private static boolean olderThan(Instant instant, Duration maxAge, Instant now) {
        return instant == null || instant.isBefore(now.minus(maxAge));
    }

    private static OperationalAlert alert(String code, long count, Instant since, String summary) {
        return new OperationalAlert(code, AlertSeverity.CRITICAL, null, count, since, summary);
    }
}
