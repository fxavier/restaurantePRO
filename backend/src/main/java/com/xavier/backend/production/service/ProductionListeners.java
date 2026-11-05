package com.xavier.backend.production.service;

import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.fiscal.model.event.FiscalDocumentReprintRequested;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Production's inputs (D35): each event runs in its own transaction after the publisher's commit, and is persisted by
 * the publication registry until it completes, so a crash means a redelivery on restart. Every handler is idempotent
 * through the ticket key ({@code production_ticket_source_uk}): a redelivery creates nothing.
 */
@Service
public class ProductionListeners {

    private final TicketService tickets;

    ProductionListeners(TicketService tickets) {
        this.tickets = tickets;
    }

    /** Tickets and jobs per destination, then {@code OrdersProductionApi.markBatchDispatched}, last. */
    @ApplicationModuleListener
    public void on(OrderBatchConfirmed event) {
        tickets.receiveBatch(event);
    }

    /** A VOID ticket on the voided line's destination. */
    @ApplicationModuleListener
    public void on(OrderLinesVoided event) {
        tickets.printVoid(event);
    }

    /** A SUBTOTAL ticket on the terminal's printer. */
    @ApplicationModuleListener
    public void on(SubtotalRequested event) {
        tickets.printSubtotal(event);
    }

    /** The ORIGINAL DOCUMENT ticket on the issuing terminal's printer. */
    @ApplicationModuleListener
    public void on(FiscalDocumentIssued event) {
        tickets.printDocument(event.documentId(), event.storeId(), event.terminalId(), event.printout(), 0);
    }

    /** A COPY DOCUMENT ticket (fiscal's copy printout already says so) on the requesting terminal's printer. */
    @ApplicationModuleListener
    public void on(FiscalDocumentReprintRequested event) {
        tickets.printDocument(event.documentId(), event.storeId(), event.terminalId(), event.printout(),
                event.copyNo());
    }
}
