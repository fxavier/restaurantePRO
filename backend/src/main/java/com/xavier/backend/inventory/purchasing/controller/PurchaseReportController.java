package com.xavier.backend.inventory.purchasing.controller;

import com.xavier.backend.inventory.model.dto.InventoryReport;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseRow;
import com.xavier.backend.inventory.purchasing.service.PurchaseReportService;
import com.xavier.backend.inventory.purchasing.service.PurchaseReportService.Grouping;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /reports/purchases?storeId&from&to&groupBy=supplier|item} (D131); any other groupBy is a 400. */
@RestController
class PurchaseReportController {

    static final String PATH = PurchasingPaths.BACKOFFICE + "/reports/purchases";

    private final PurchaseReportService reports;

    PurchaseReportController(PurchaseReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = PATH, params = "groupBy=supplier")
    InventoryReport<PurchaseRow> bySupplier(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                            @RequestParam LocalDate to) {
        return reports.purchases(storeId, from, to, Grouping.SUPPLIER);
    }

    @GetMapping(value = PATH, params = "groupBy=item")
    InventoryReport<PurchaseRow> byItem(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                        @RequestParam LocalDate to) {
        return reports.purchases(storeId, from, to, Grouping.ITEM);
    }
}
