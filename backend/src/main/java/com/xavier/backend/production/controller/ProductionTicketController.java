package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.TicketRouting;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.model.dto.TicketView;
import com.xavier.backend.production.service.ProductionTicketService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production tickets on the POS (the session's store) and in BackOffice: list them ({@code routing=UNROUTED} for the
 * work still waiting for a printer) and route an UNROUTED ticket (D89 (1)).
 */
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

    @GetMapping(BACKOFFICE)
    PageResponse<TicketView> list(@RequestParam UUID storeId, @RequestParam(required = false) TicketRouting routing,
                                  @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                  Pageable pageable) {
        return tickets.listForBackOffice(storeId, routing, pageable);
    }

    @GetMapping(POS)
    PageResponse<TicketView> listForPos(@RequestParam(required = false) TicketRouting routing,
                                        @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                        Pageable pageable) {
        return tickets.listForPos(routing, pageable);
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
