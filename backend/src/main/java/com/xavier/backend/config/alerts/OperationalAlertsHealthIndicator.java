package com.xavier.backend.config.alerts;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Health component {@code operationalAlerts} (D149): {@code ALERT} while any alert is active, else {@code UP}.
 * {@code ALERT} is not in the aggregation order (like fiscal {@code NON_CERTIFIED}, D54), so an alert never turns the
 * overall status DOWN and never makes the container unhealthy. Reads the 30 s cached snapshot.
 */
@Component("operationalAlertsHealthIndicator")
class OperationalAlertsHealthIndicator implements HealthIndicator {

    static final Status ALERT = new Status("ALERT", "Operational alerts are active: see /api/v1/backoffice/alerts");

    private final AlertsService alerts;

    OperationalAlertsHealthIndicator(AlertsService alerts) {
        this.alerts = alerts;
    }

    @Override
    public Health health() {
        int active = alerts.current().alerts().size();
        return Health.status(active == 0 ? Status.UP : ALERT).withDetail("alerts", active).build();
    }
}
