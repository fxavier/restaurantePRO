package com.xavier.backend.config.alerts;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** D149: the in-app list of operational alerts, for {@code SYSTEM_MONITOR} holders, filtered by their stores. */
@RestController
class AlertsController {

    static final String PATH = "/api/v1/backoffice/alerts";

    private final AlertsService alerts;

    AlertsController(AlertsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping(PATH)
    AlertsView list() {
        return alerts.list();
    }
}
