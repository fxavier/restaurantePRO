package com.xavier.backend.config.alerts;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The backup status file of D151, schema version 1, as {@code deploy/verify-backups.sh} writes it. A missing instant
 * is {@code null} (never happened).
 *
 * @param problems    short sentences without secrets; never shown in an alert
 * @param filesystems {@code backups} and {@code docker} (Docker's root directory)
 */
record BackupStatus(Instant checkedAt, boolean ok, Instant lastLogicalBackupAt, Instant lastBaseBackupAt,
                    Instant lastOffboxSyncAt, List<String> problems, List<Filesystem> filesystems) {

    static final int SCHEMA_VERSION = 1;

    BackupStatus {
        problems = List.copyOf(problems);
        filesystems = List.copyOf(filesystems);
    }

    /** How many problems the check reported; a check that is not ok without naming one counts as one. */
    long problemCount() {
        return problems.isEmpty() && !ok ? 1 : problems.size();
    }

    /**
     * Parses schema 1.
     *
     * @throws IllegalArgumentException for another schema version, a missing or mistyped field
     */
    static BackupStatus parse(JsonNode root) {
        require(root.isObject(), "not a JSON object");
        JsonNode version = root.get("schemaVersion");
        require(version != null && version.isIntegralNumber() && version.intValue() == SCHEMA_VERSION,
                "schemaVersion is not " + SCHEMA_VERSION);
        Instant checkedAt = instant(root, "checkedAt");
        require(checkedAt != null, "checkedAt is missing");
        JsonNode ok = root.get("ok");
        require(ok != null && ok.isBoolean(), "ok is not a boolean");
        return new BackupStatus(checkedAt, ok.booleanValue(), instant(root, "lastLogicalBackupAt"),
                instant(root, "lastBaseBackupAt"), instant(root, "lastOffboxSyncAt"), problems(root),
                filesystems(root));
    }

    private static Instant instant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        require(value.isString(), field + " is not an ISO-8601 instant");
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 instant");
        }
    }

    private static List<String> problems(JsonNode root) {
        JsonNode problems = root.get("problems");
        require(problems != null && problems.isArray(), "problems is not an array");
        List<String> texts = new ArrayList<>();
        for (JsonNode problem : problems) {
            require(problem.isString(), "a problem is not a string");
            texts.add(problem.asString());
        }
        return texts;
    }

    private static List<Filesystem> filesystems(JsonNode root) {
        JsonNode filesystems = root.get("filesystems");
        require(filesystems != null && filesystems.isArray(), "filesystems is not an array");
        List<Filesystem> parsed = new ArrayList<>();
        for (JsonNode filesystem : filesystems) {
            JsonNode name = filesystem.get("name");
            JsonNode total = filesystem.get("totalBytes");
            JsonNode free = filesystem.get("freeBytes");
            require(name != null && name.isString() && !name.asString().isBlank(), "a filesystem has no name");
            require(total != null && total.isIntegralNumber() && free != null && free.isIntegralNumber(),
                    "a filesystem's sizes are not integers");
            require(free.longValue() >= 0 && free.longValue() <= total.longValue(),
                    "a filesystem's free bytes are not between 0 and its total");
            parsed.add(new Filesystem(name.asString(), total.longValue(), free.longValue()));
        }
        return parsed;
    }

    private static void require(boolean condition, String problem) {
        if (!condition) {
            throw new IllegalArgumentException(problem);
        }
    }

    /** One filesystem's size; {@code name} is a fixed word ({@code backups}, {@code docker}, {@code app}). */
    record Filesystem(String name, long totalBytes, long freeBytes) {

        /** Whether less than {@code percent} % is free; an unknown (zero) size is never low. */
        boolean freeBelow(int percent) {
            return totalBytes > 0 && freeBytes * 100 < totalBytes * percent;
        }

        long freePercent() {
            return totalBytes == 0 ? 100 : freeBytes * 100 / totalBytes;
        }

        double freeRatio() {
            return totalBytes == 0 ? Double.NaN : (double) freeBytes / totalBytes;
        }
    }
}
