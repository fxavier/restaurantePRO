package com.xavier.backend.inventory.count.controller;

import com.xavier.backend.inventory.count.model.dto.DivergenceRow;
import com.xavier.backend.inventory.count.service.CountDivergenceService;
import com.xavier.backend.inventory.model.dto.InventoryReport;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The count divergences report ({@code INVENTORY_VIEW}), next to the other stock reports. */
@RestController
class CountReportController {

    static final String PATH = "/api/v1/backoffice/inventory/reports/count-divergences";

    private final CountDivergenceService divergences;

    CountReportController(CountDivergenceService divergences) {
        this.divergences = divergences;
    }

    @GetMapping(PATH)
    InventoryReport<DivergenceRow> divergences(@RequestParam UUID storeId, @RequestParam LocalDate from,
                                               @RequestParam LocalDate to) {
        return divergences.divergences(storeId, from, to);
    }
}
