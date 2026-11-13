package com.xavier.backend.production.controller;

import com.xavier.backend.production.model.dto.PrinterPresetView;
import com.xavier.backend.production.service.PrinterService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The printer profile presets of D154 ({@code PRODUCTION_MANAGE} in some store). */
@RestController
class PrinterProfileController {

    private final PrinterService printers;

    PrinterProfileController(PrinterService printers) {
        this.printers = printers;
    }

    @GetMapping("/api/v1/backoffice/printer-profiles")
    List<PrinterPresetView> presets() {
        return printers.presets();
    }
}
