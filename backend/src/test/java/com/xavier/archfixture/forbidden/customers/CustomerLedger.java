package com.xavier.archfixture.forbidden.customers;

import com.xavier.archfixture.forbidden.cash.CashDrawer;
import com.xavier.archfixture.forbidden.fiscal.Invoice;
import com.xavier.archfixture.forbidden.orders.OrderTotals;
import com.xavier.archfixture.forbidden.production.Ticket;
import com.xavier.archfixture.forbidden.settlement.Tender;

/** Test fixture only: customers → orders, settlement, fiscal, cash and production, all forbidden by D65. */
public class CustomerLedger {
    private final OrderTotals orders = new OrderTotals();
    private final Tender tender = new Tender();
    private final Invoice invoice = new Invoice();
    private final CashDrawer drawer = new CashDrawer();
    private final Ticket ticket = new Ticket();

    public void post() {
        orders.close();
        tender.settle();
        invoice.issue();
        drawer.count();
        ticket.print();
    }
}
