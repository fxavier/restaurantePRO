package com.xavier.archfixture.forbidden.orders;

import com.xavier.archfixture.forbidden.production.Ticket;
import com.xavier.archfixture.forbidden.settlement.Tender;

/** Test fixture only: orders → settlement and orders → production, both forbidden by D35. */
public class OrderTotals {

    private final Tender tender = new Tender();
    private final Ticket ticket = new Ticket();

    public void close() {
        tender.settle();
        ticket.print();
    }
}
