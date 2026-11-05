package com.xavier.backend.cash.controller;

import com.xavier.backend.cash.model.dto.ReconciliationLine;
import com.xavier.backend.cash.service.CashReconciliationService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class CashReconciliationController {

    private final CashReconciliationService reconciliation;

    CashReconciliationController(CashReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /** Business dates {@code from} to {@code to}, both included (D85: never a UTC range). */
    @GetMapping("/api/v1/backoffice/cash/reconciliation")
    List<ReconciliationLine> reconcile(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                       @RequestParam LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > CashReconciliationService.MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "to may be at most " + CashReconciliationService.MAX_RANGE_DAYS + " days after from");
        }
        return reconciliation.reconcile(storeId, from, to);
    }
}
