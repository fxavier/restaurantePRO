package com.xavier.backend.fiscal.adapter;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Health component {@code fiscal} (D54): {@code UP} for a certified adapter, {@code NON_CERTIFIED} otherwise (B4).
 * {@code NON_CERTIFIED} is not in the aggregation order, so it never turns the overall status DOWN.
 */
@Component("fiscalHealthIndicator")
class FiscalHealthIndicator implements HealthIndicator {

    static final Status NON_CERTIFIED = new Status("NON_CERTIFIED",
            "The fiscal adapter is not certified: documents are not fiscal (B4)");

    private final ActiveFiscalAdapter adapter;

    FiscalHealthIndicator(ActiveFiscalAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Health health() {
        return Health.status(adapter.certified() ? Status.UP : NON_CERTIFIED)
                .withDetail("adapter", adapter.code())
                .withDetail("certified", adapter.certified())
                .build();
    }
}
