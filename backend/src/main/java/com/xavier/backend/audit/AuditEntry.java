package com.xavier.backend.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What happened, for the append-only audit trail (spec §10: actor, approver, action, entity, store,
 * terminal, instant, reason). The instant is taken from the application {@code Clock} when recorded.
 *
 * @param actorId    who did it; {@code null} only for the system itself (bootstrap, scheduled jobs)
 * @param approverId supervisor whose credentials authorized it (D28), or {@code null}
 * @param action     past-tense verb in UPPER_SNAKE, e.g. {@code STORE_UPDATED}
 * @param entityType aggregate name from CONTEXT.md, e.g. {@code Store}
 * @param entityId   id of that aggregate (a UUID as text for every R1 aggregate)
 * @param reason     free text the user gave, or {@code null}
 * @param details    small before/after facts only when needed; never PINs, passwords, tokens or card data
 */
public record AuditEntry(UUID actorId, UUID approverId, String action, String entityType, String entityId,
                         UUID storeId, UUID terminalId, String reason, Map<String, Object> details) {

    private static final Set<String> SECRET_WORDS =
            Set.of("pin", "password", "passwd", "secret", "token", "card", "pan", "cvv", "cvc");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");

    public AuditEntry {
        requireText(action, "action");
        requireText(entityType, "entityType");
        requireText(entityId, "entityId");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        details.keySet().forEach(AuditEntry::rejectSecret);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void rejectSecret(String key) {
        for (String word : WORD_BOUNDARY.split(key)) {
            if (SECRET_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Audit details must not contain secrets: '" + key + "'");
            }
        }
    }
}
