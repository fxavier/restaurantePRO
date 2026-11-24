package com.xavier.backend.settlement.controller;

import com.xavier.backend.settlement.model.dto.SettlementAlertsView;
import com.xavier.backend.settlement.service.SettlementAlertsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The POS's payments to confirm for the session's store (footer of {@code /tables}). */
@RestController
class SettlementAlertsController {

    private final SettlementAlertsService alerts;

    SettlementAlertsController(SettlementAlertsService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/api/v1/pos/settlement/alerts")
    SettlementAlertsView settlementAlerts() {
        return alerts.alertsForPos();
    }
}
