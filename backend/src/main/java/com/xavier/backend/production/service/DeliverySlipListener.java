package com.xavier.backend.production.service;

import com.xavier.backend.orders.model.event.DeliveryDispatched;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Prints a dispatched delivery's slip (D129) through the one print queue, after orders' commit. Idempotent through
 * the ticket key: a redelivered event prints nothing more, a re-dispatch (a new delivery event) prints again.
 */
@Service
public class DeliverySlipListener {

    private final TicketService tickets;

    DeliverySlipListener(TicketService tickets) {
        this.tickets = tickets;
    }

    @ApplicationModuleListener
    public void on(DeliveryDispatched event) {
        tickets.printDeliverySlip(event);
    }
}
