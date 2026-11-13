package com.xavier.backend.production.service;

import com.xavier.backend.production.repository.StoreJobCount;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * D150, the print queue's two alerts per store (WARNING): {@code PRINT_JOBS_ATTENTION}, FAILED or UNKNOWN jobs of the
 * last 24 h that nobody retried or acknowledged; {@code PRINT_QUEUE_STALLED}, jobs waiting over 5 min on a NORMAL
 * printer. A printer that is off shows up here through its jobs (no per-printer alert). Counts only, never a job id.
 */
@Component
public class PrintQueueAlertSource implements AlertSource {

    static final String ATTENTION = "PRINT_JOBS_ATTENTION";
    static final String STALLED = "PRINT_QUEUE_STALLED";

    private final ProductionMetrics metrics;

    PrintQueueAlertSource(ProductionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public List<OperationalAlert> alerts() {
        ProductionMetrics.QueueHealth health = metrics.refreshQueue();
        List<OperationalAlert> alerts = new ArrayList<>();
        for (StoreJobCount unresolved : health.unresolved()) {
            alerts.add(new OperationalAlert(ATTENTION, AlertSeverity.WARNING, unresolved.storeId(),
                    unresolved.jobs(), unresolved.oldestCreatedAt(), unresolved.jobs()
                    + " print job(s) failed or have an unknown outcome: retry or acknowledge them."));
        }
        for (StoreJobCount waiting : health.waiting()) {
            if (waiting.late() > 0) {
                alerts.add(new OperationalAlert(STALLED, AlertSeverity.WARNING, waiting.storeId(), waiting.late(),
                        waiting.oldestCreatedAt(), waiting.late()
                        + " print job(s) have waited over 5 minutes on a printer in NORMAL mode."));
            }
        }
        return alerts;
    }
}
