package com.xavier.backend.shared.ops;

import java.time.Instant;
import java.util.UUID;

/**
 * One active operational alert (D149). Carries counts and stores only, never an order, payment, job or document id
 * (§12), and no secret in {@code summary}.
 *
 * @param code     stable alert code of D150, e.g. {@code EXPORT_FAILED}
 * @param severity how urgent it is
 * @param storeId  the store it concerns; {@code null} = installation-wide
 * @param count    how many things are in the alert's condition
 * @param since    when the condition started (or the latest occurrence, when the source keeps only that)
 * @param summary  a short human sentence
 */
public record OperationalAlert(String code, AlertSeverity severity, UUID storeId, long count, Instant since,
                               String summary) {
}
