package com.xavier.backend.production.controller;

import com.xavier.backend.production.model.dto.RoutesRequest;
import com.xavier.backend.production.model.dto.RoutesView;
import com.xavier.backend.production.model.dto.TerminalPrintersRequest;
import com.xavier.backend.production.model.dto.TerminalPrintersView;
import com.xavier.backend.production.service.RoutingService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice routing tables of a store (D56): destinations and terminals to printers. */
@RestController
class RoutingController {

    static final String ROUTES = "/api/v1/backoffice/production-routes";
    static final String TERMINAL_PRINTERS = "/api/v1/backoffice/terminal-printers";

    private final RoutingService routing;

    RoutingController(RoutingService routing) {
        this.routing = routing;
    }

    @GetMapping(ROUTES)
    RoutesView routes(@RequestParam UUID storeId) {
        return routing.routes(storeId);
    }

    @PutMapping(ROUTES)
    RoutesView replaceRoutes(@Valid @RequestBody RoutesRequest body) {
        return routing.replaceRoutes(body);
    }

    @GetMapping(TERMINAL_PRINTERS)
    TerminalPrintersView terminalPrinters(@RequestParam UUID storeId) {
        return routing.terminalPrinters(storeId);
    }

    @PutMapping(TERMINAL_PRINTERS)
    TerminalPrintersView replaceTerminalPrinters(@Valid @RequestBody TerminalPrintersRequest body) {
        return routing.replaceTerminalPrinters(body);
    }
}
