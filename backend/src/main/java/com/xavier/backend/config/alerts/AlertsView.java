package com.xavier.backend.config.alerts;

import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Instant;
import java.util.List;

/** {@code GET /api/v1/backoffice/alerts}: the visible alerts, CRITICAL first, as collected at {@code generatedAt}. */
record AlertsView(Instant generatedAt, List<OperationalAlert> alerts) {
}
