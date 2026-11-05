package com.xavier.backend.reporting.controller;

import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.service.ReportService;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/backoffice/reports/…} ({@code REPORT_VIEW} in the store): {@code from} and {@code to} are
 * business dates (ISO), both inclusive (D85).
 */
@RestController
@RequestMapping("/api/v1/backoffice/reports")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/receipts")
    ReportView<ReceiptsRow> receipts(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                     @RequestParam LocalDate to,
                                     @RequestParam @Pattern(regexp = "method|employee|day") String groupBy) {
        return reports.receipts(storeId, from, to, groupBy);
    }
}
