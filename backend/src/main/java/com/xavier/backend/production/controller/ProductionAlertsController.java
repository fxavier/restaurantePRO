package com.xavier.backend.production.controller;

import com.xavier.backend.production.model.dto.ProductionAlertsView;
import com.xavier.backend.production.service.ProductionAlertsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The POS's print and kitchen alerts for the session's store (status bar and footer of {@code /tables}). */
@RestController
class ProductionAlertsController {

    private final ProductionAlertsService alerts;

    ProductionAlertsController(ProductionAlertsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/api/v1/pos/production/alerts")
    ProductionAlertsView productionAlerts() {
        return alerts.alertsForPos();
    }
}
