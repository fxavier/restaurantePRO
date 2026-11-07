package com.xavier.backend.inventory.controller;

import com.xavier.backend.inventory.model.dto.BelowMinimumRow;
import com.xavier.backend.inventory.model.dto.InventoryReport;
import com.xavier.backend.inventory.model.dto.RotationRow;
import com.xavier.backend.inventory.model.dto.ValuationRow;
import com.xavier.backend.inventory.model.dto.WasteRow;
import com.xavier.backend.inventory.service.InventoryReportService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stock reports (D131) as JSON, or as CSV with {@code format=csv} ({@code REPORT_EXPORT} too, audited). Waste and
 * rotation take a period of business days.
 */
@RestController
class InventoryReportController {

    static final String REPORTS = InventoryPaths.BACKOFFICE + "/reports";
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final InventoryReportService reports;

    InventoryReportController(InventoryReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = REPORTS + "/valuation", params = "!format")
    InventoryReport<ValuationRow> valuation(@RequestParam UUID storeId) {
        return reports.valuation(storeId);
    }

    @GetMapping(value = REPORTS + "/below-minimum", params = "!format")
    InventoryReport<BelowMinimumRow> belowMinimum(@RequestParam UUID storeId) {
        return reports.belowMinimum(storeId);
    }

    @GetMapping(value = REPORTS + "/waste", params = "!format")
    InventoryReport<WasteRow> waste(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                    @RequestParam LocalDate to) {
        return reports.waste(storeId, from, to);
    }

    @GetMapping(value = REPORTS + "/rotation", params = "!format")
    InventoryReport<RotationRow> rotation(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                          @RequestParam LocalDate to) {
        return reports.rotation(storeId, from, to);
    }

    @GetMapping(value = REPORTS + "/{report:valuation|below-minimum|waste|rotation}", params = "format=csv")
    ResponseEntity<String> csv(@PathVariable String report, @RequestParam UUID storeId,
                               @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        boolean periodic = report.equals("waste") || report.equals("rotation");
        String csv = reports.csv(report, storeId, periodic ? from : null, periodic ? to : null);
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("inventory-" + report + (periodic ? "-" + from + "-" + to : "") + ".csv").build()
                        .toString())
                .body(csv);
    }
}
