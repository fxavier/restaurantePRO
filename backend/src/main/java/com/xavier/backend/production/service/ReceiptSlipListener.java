package com.xavier.backend.production.service;

import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Prints a debt payment's non-fiscal slip (D75) through the one print queue, after settlement's commit. Idempotent
 * through the ticket key: a redelivered event prints nothing more.
 */
@Service
public class ReceiptSlipListener {

    private final TicketService tickets;

    ReceiptSlipListener(TicketService tickets) {
        this.tickets = tickets;
    }

    @ApplicationModuleListener
    public void on(ReceiptSlipRequested event) {
        tickets.printReceiptSlip(event.paymentId(), event.storeId(), event.terminalId(), event.lines());
    }
}
