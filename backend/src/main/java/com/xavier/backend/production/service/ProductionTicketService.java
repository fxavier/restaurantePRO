package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What people do with production tickets (D89 (1)): route an UNROUTED one once its route or terminal printer exists.
 * POS calls take the store from the session (D39); BackOffice calls follow the ticket's store. A ticket addressed by
 * id: 404, then 403 (D42).
 */
@Service
public class ProductionTicketService {

    private final ProductionTicketRepository tickets;
    private final TicketService ticketService;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;

    ProductionTicketService(ProductionTicketRepository tickets, TicketService ticketService,
                            AccessPolicy accessPolicy, AuditLog auditLog) {
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
    }

    @Transactional
    public PrintJobView routeFromBackOffice(UUID ticketId) {
        accessPolicy.require(Permission.PRODUCTION_OPERATE, ticketStore(ticketId));
        return route(ticketId);
    }

    @Transactional
    public PrintJobView routeFromPos(UUID ticketId) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_OPERATE);
        accessPolicy.requireSessionStore(pos, ticketStore(ticketId), PrintJobService.TICKET, ticketId);
        return route(ticketId);
    }

    private PrintJobView route(UUID ticketId) {
        PrintJob job = ticketService.route(tickets.findById(ticketId).orElseThrow());
        ProductionTicket routed = tickets.findById(ticketId).orElseThrow();
        auditLog.recordAsCurrentActor("PRODUCTION_TICKET_ROUTED", PrintJobService.TICKET, ticketId.toString(),
                routed.getStoreId(), null, null, Map.of("printerId", routed.getPrinterId().toString(),
                        "printJobId", job.getId().toString()));
        return job.toView(routed);
    }

    private UUID ticketStore(UUID ticketId) {
        return tickets.findStoreIdById(ticketId)
                .orElseThrow(() -> new ProductionNotFoundException(PrintJobService.TICKET, ticketId));
    }
}
