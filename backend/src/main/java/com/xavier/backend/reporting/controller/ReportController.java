package com.xavier.backend.reporting.controller;

import com.xavier.backend.reporting.model.dto.DiscountRow;
import com.xavier.backend.reporting.model.dto.MarginRow;
import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportStatus;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.model.dto.SalesRow;
import com.xavier.backend.reporting.model.dto.TaxRow;
import com.xavier.backend.reporting.model.dto.VoidsRow;
import com.xavier.backend.reporting.service.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @GetMapping("/status")
    ReportStatus status(@RequestParam UUID storeId) {
        return reports.status(storeId);
    }

    @GetMapping("/sales")
    ReportView<SalesRow> sales(@RequestParam UUID storeId, @RequestParam LocalDate from, @RequestParam LocalDate to,
                               @RequestParam @Pattern(regexp = "day|hour|terminal|employee|area|item|family|mode")
                               String groupBy) {
        return reports.sales(storeId, from, to, groupBy);
    }

    @GetMapping("/taxes")
    ReportView<TaxRow> taxes(@RequestParam UUID storeId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.taxes(storeId, from, to);
    }

    @GetMapping("/top-items")
    ReportView<SalesRow> topItems(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                  @RequestParam LocalDate to,
                                  @RequestParam(defaultValue = "quantity") @Pattern(regexp = "quantity|gross") String by,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return reports.topItems(storeId, from, to, by, limit);
    }

    @GetMapping("/voids")
    ReportView<VoidsRow> voids(@RequestParam UUID storeId, @RequestParam LocalDate from, @RequestParam LocalDate to,
                               @RequestParam @Pattern(regexp = "reason|employee|item|approver") String groupBy) {
        return reports.voids(storeId, from, to, groupBy);
    }

    @GetMapping("/margins")
    ReportView<MarginRow> margins(@RequestParam UUID storeId, @RequestParam LocalDate from, @RequestParam LocalDate to,
                                  @RequestParam @Pattern(regexp = "item|family|day") String groupBy) {
        return reports.margins(storeId, from, to, groupBy);
    }

    @GetMapping("/discounts")
    ReportView<DiscountRow> discounts(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                      @RequestParam LocalDate to,
                                      @RequestParam @Pattern(regexp = "origin|promotion|employee|approver|item")
                                      String groupBy) {
        return reports.discounts(storeId, from, to, groupBy);
    }

    @GetMapping("/receipts")
    ReportView<ReceiptsRow> receipts(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                     @RequestParam LocalDate to,
                                     @RequestParam @Pattern(regexp = "method|employee|day") String groupBy) {
        return reports.receipts(storeId, from, to, groupBy);
    }
}
