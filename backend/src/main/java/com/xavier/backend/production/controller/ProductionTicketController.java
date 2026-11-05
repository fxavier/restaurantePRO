package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.service.ProductionTicketService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Production tickets on the POS (the session's store) and in BackOffice: route an UNROUTED ticket (D89 (1)). */
@RestController
class ProductionTicketController {

    static final String BACKOFFICE = "/api/v1/backoffice/production-tickets";
    static final String POS = "/api/v1/pos/production-tickets";

    private final ProductionTicketService tickets;
    private final IdempotentRequests idempotent;

    ProductionTicketController(ProductionTicketService tickets, IdempotentRequests idempotent) {
        this.tickets = tickets;
        this.idempotent = idempotent;
    }

    @PostMapping(BACKOFFICE + "/{id}/route")
    ResponseEntity<PrintJobView> route(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       HttpServletRequest request) {
        return idempotent.execute("production.ticket.route", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(tickets.routeFromBackOffice(id)));
    }

    @PostMapping(POS + "/{id}/route")
    ResponseEntity<PrintJobView> routeFromPos(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                              HttpServletRequest request) {
        return idempotent.execute("production.ticket.route", key, request, null, PrintJobView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(tickets.routeFromPos(id)));
    }
}
