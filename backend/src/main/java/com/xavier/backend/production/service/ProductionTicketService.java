package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketRouting;
import com.xavier.backend.production.model.dto.PrintJobView;
import com.xavier.backend.production.model.dto.TicketView;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.web.PageResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What people do with production tickets (D89 (1)): list them (the UNROUTED ones above all), and route an UNROUTED
 * one once its route or terminal printer exists.
 * POS calls take the store from the session (D39); BackOffice calls follow the ticket's store. A ticket addressed by
 * id: 404, then 403 (D42).
 */
@Service
public class ProductionTicketService {

    private final ProductionTicketRepository tickets;
    private final TicketService ticketService;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final AuditLog auditLog;

    ProductionTicketService(ProductionTicketRepository tickets, TicketService ticketService,
                            AccessPolicy accessPolicy, StoreProbe probe, AuditLog auditLog) {
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketView> listForBackOffice(UUID storeId, TicketRouting routing, Pageable pageable) {
        accessPolicy.require(Permission.PRODUCTION_VIEW, storeId);
        return list(storeId, routing, pageable);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketView> listForPos(TicketRouting routing, Pageable pageable) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_VIEW);
        return list(pos.storeId(), routing, pageable);
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

    private PageResponse<TicketView> list(UUID storeId, TicketRouting routing, Pageable pageable) {
        Page<ProductionTicket> page = routing == null ? tickets.findByStoreId(storeId, pageable)
                : tickets.findByStoreIdAndRouting(storeId, routing, pageable);
        return PageResponse.from(page.map(ProductionTicket::toView));
    }

    private UUID ticketStore(UUID ticketId) {
        return probe.storeOf(ProductionTicketRepository.TABLE, ticketId)
                .orElseThrow(() -> new ProductionNotFoundException(PrintJobService.TICKET, ticketId));
    }
}
