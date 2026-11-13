package com.xavier.backend.settlement.service;

import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * D150 PAYMENTS_IN_DOUBT: per store, the attempts in doubt for more than 30 minutes ({@link InDoubtGauge#ALERT_AFTER})
 * as of the gauge's last refresh, the signal of {@code payment_attempts_in_doubt_overdue{store}}. CRITICAL: a person
 * must query the provider or resolve them (D81). No query of its own.
 */
@Component
class InDoubtAlertSource implements AlertSource {

    private final InDoubtGauge gauge;

    InDoubtAlertSource(InDoubtGauge gauge) {
        this.gauge = gauge;
    }

    @Override
    public List<OperationalAlert> alerts() {
        return gauge.overdueAtLastRefresh().stream()
                .map(store -> new OperationalAlert("PAYMENTS_IN_DOUBT", AlertSeverity.CRITICAL, store.storeId(),
                        store.attempts(), store.oldestCreatedAt(), store.attempts()
                        + " payment attempt(s) in doubt for more than 30 min: query the provider or resolve them."))
                .toList();
    }
}
