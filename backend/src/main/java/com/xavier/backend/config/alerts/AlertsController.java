package com.xavier.backend.config.alerts;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D149: the in-app list of a tenant's operational alerts, for its {@code SYSTEM_MONITOR} holders, filtered by their
 * stores; and the platform's alerts for platform operators ({@code /api/v1/platform/**} admits only an ops-realm token
 * holding {@code platform-operator}, {@code TenantResolutionFilter}).
 */
@RestController
class AlertsController {

    static final String PATH = "/api/v1/backoffice/alerts";
    static final String PLATFORM_PATH = "/api/v1/platform/alerts";

    private final AlertsService alerts;

    AlertsController(AlertsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping(PATH)
    AlertsView list() {
        return alerts.list();
    }

    @GetMapping(PLATFORM_PATH)
    AlertsView platformList() {
        return alerts.platformList();
    }
}
