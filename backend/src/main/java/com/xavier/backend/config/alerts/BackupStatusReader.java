package com.xavier.backend.config.alerts;

import com.xavier.backend.config.alerts.BackupStatus.Filesystem;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the backup status file of D151 ({@code restaurantpro.alerts.backup-status-file}; unset = no backup alerts) at
 * most once a minute. A missing, unreadable, malformed or unknown-schema file is "no status" (the monitor is stale),
 * logged at WARN once until the file is usable again. Gauges (D150): {@code backup_status_age_seconds} (+Inf without a
 * status), {@code backup_last_success_age_seconds{kind=logical|base|offbox}} (+Inf for one never taken),
 * {@code backup_problems}, and {@code filesystem_free_ratio{name=backups|docker|app}}; {@code app} is the filesystem of
 * the JVM's working directory and is measured even without a file. NaN means unknown.
 */
@Component
class BackupStatusReader {

    static final Duration READ_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(BackupStatusReader.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Function<BackupStatus, Instant>> BACKUP_KINDS = Map.of(
            "logical", BackupStatus::lastLogicalBackupAt,
            "base", BackupStatus::lastBaseBackupAt,
            "offbox", BackupStatus::lastOffboxSyncAt);

    /** The last read: a usable {@code status}, or {@code null} and since when the file has been unusable. */
    record Reading(BackupStatus status, Instant unusableSince) {
    }

    private final Path file;
    private final Supplier<Optional<Filesystem>> app;
    private final Clock clock;
    private Instant readAt;
    private Reading reading;

    @Autowired
    BackupStatusReader(@Value("${restaurantpro.alerts.backup-status-file:}") String file, MeterRegistry meters,
                       Clock clock) {
        this(file.isBlank() ? null : Path.of(file), BackupStatusReader::workingDirectory, meters, clock);
    }

    /** @param file {@code null} when no file is configured */
    BackupStatusReader(Path file, Supplier<Optional<Filesystem>> app, MeterRegistry meters, Clock clock) {
        this.file = file;
        this.app = app;
        this.clock = clock;
        Gauge.builder("filesystem_free_ratio", this, reader -> reader.app.get().map(Filesystem::freeRatio)
                        .orElse(Double.NaN))
                .tag("name", "app").description("Free share of a filesystem").register(meters);
        if (file != null) {
            registerBackupGauges(meters);
        }
    }

    boolean configured() {
        return file != null;
    }

    /** The current reading, from the file read at most {@link #READ_INTERVAL} ago. Only when {@link #configured}. */
    synchronized Reading read() {
        Instant now = clock.instant();
        if (readAt == null || !now.isBefore(readAt.plus(READ_INTERVAL))) {
            reading = readFile(now);
            readAt = now;
        }
        return reading;
    }

    /** The filesystems of the status file (none without a usable one), then the application's. */
    List<Filesystem> filesystems() {
        List<Filesystem> filesystems = new ArrayList<>();
        if (configured()) {
            Optional.ofNullable(read().status()).ifPresent(status -> filesystems.addAll(status.filesystems()));
        }
        app.get().ifPresent(filesystems::add);
        return filesystems;
    }

    private Reading readFile(Instant now) {
        try {
            BackupStatus status = BackupStatus.parse(JSON.readTree(Files.readString(file)));
            return new Reading(status, null);
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            Instant since = reading == null || reading.unusableSince() == null ? now : reading.unusableSince();
            if (since.equals(now)) {
                log.warn("The backup status file {} is missing or unusable ({}): the backup monitor is treated as "
                        + "stale until verify-backups.sh writes a valid one (D151)", file, e.getMessage());
            }
            return new Reading(null, since);
        }
    }

    private void registerBackupGauges(MeterRegistry meters) {
        Gauge.builder("backup_status_age_seconds", this, reader -> reader.status()
                        .map(status -> reader.ageSeconds(status.checkedAt())).orElse(Double.POSITIVE_INFINITY))
                .description("Age of the last backup check (verify-backups.sh)").register(meters);
        BACKUP_KINDS.forEach((kind, instant) -> Gauge.builder("backup_last_success_age_seconds", this,
                        reader -> reader.status().map(status -> reader.ageSeconds(instant.apply(status)))
                                .orElse(Double.NaN))
                .tag("kind", kind).description("Age of the last successful backup of a kind").register(meters));
        Gauge.builder("backup_problems", this, reader -> reader.status()
                        .map(status -> (double) status.problemCount()).orElse(Double.NaN))
                .description("Problems listed by the last backup check").register(meters);
        for (String name : List.of("backups", "docker")) {
            Gauge.builder("filesystem_free_ratio", this, reader -> reader.status()
                            .flatMap(status -> status.filesystems().stream()
                                    .filter(filesystem -> filesystem.name().equals(name)).findFirst())
                            .map(Filesystem::freeRatio).orElse(Double.NaN))
                    .tag("name", name).description("Free share of a filesystem").register(meters);
        }
    }

    private Optional<BackupStatus> status() {
        return Optional.ofNullable(read().status());
    }

    /** Seconds since {@code instant}; +Inf for {@code null} (never). */
    private double ageSeconds(Instant instant) {
        if (instant == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Duration.between(instant, clock.instant()).toMillis() / 1000.0;
    }

    private static Optional<Filesystem> workingDirectory() {
        try {
            FileStore store = Files.getFileStore(Path.of("").toAbsolutePath());
            return Optional.of(new Filesystem("app", store.getTotalSpace(), store.getUsableSpace()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
