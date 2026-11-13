package com.xavier.backend.config.alerts;

import com.xavier.backend.config.alerts.BackupStatus.Filesystem;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * D150 DISK_SPACE_LOW, installation-wide: the filesystems of the backup status file and the application's own (see
 * {@link BackupStatusReader#filesystems}). Below 15 % free is a WARNING, below 5 % CRITICAL; one alert at the worst
 * severity, counting the low filesystems.
 */
@Component
class DiskAlertSource implements AlertSource {

    static final int WARNING_BELOW_PERCENT = 15;
    static final int CRITICAL_BELOW_PERCENT = 5;

    private final BackupStatusReader reader;
    private final Clock clock;

    DiskAlertSource(BackupStatusReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public List<OperationalAlert> alerts() {
        List<Filesystem> low = reader.filesystems().stream()
                .filter(filesystem -> filesystem.freeBelow(WARNING_BELOW_PERCENT)).toList();
        if (low.isEmpty()) {
            return List.of();
        }
        AlertSeverity severity = low.stream().anyMatch(filesystem -> filesystem.freeBelow(CRITICAL_BELOW_PERCENT))
                ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
        String which = low.stream().map(filesystem -> filesystem.name() + " " + filesystem.freePercent() + "% free")
                .collect(Collectors.joining(", "));
        return List.of(new OperationalAlert("DISK_SPACE_LOW", severity, null, low.size(), clock.instant(),
                "Disk space low: " + which + "."));
    }
}
