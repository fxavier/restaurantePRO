package com.xavier.archfixture.forbidden.inventory;

import com.xavier.archfixture.forbidden.cash.CashDrawer;
import com.xavier.archfixture.forbidden.customers.Customer;
import com.xavier.archfixture.forbidden.dining.TableMap;
import com.xavier.archfixture.forbidden.fiscal.Invoice;
import com.xavier.archfixture.forbidden.production.Ticket;
import com.xavier.archfixture.forbidden.promotions.PromotionRules;
import com.xavier.archfixture.forbidden.settlement.Tender;

/**
 * Test fixture only: inventory → settlement, fiscal, cash, customers, promotions, dining and production, all
 * forbidden by D99.
 */
public class StockLeak {
    private final Tender tender = new Tender();
    private final Invoice invoice = new Invoice();
    private final CashDrawer drawer = new CashDrawer();
    private final Customer customer = new Customer();
    private final PromotionRules promotions = new PromotionRules();
    private final TableMap tables = new TableMap();
    private final Ticket ticket = new Ticket();

    public void consume() {
        tender.settle();
        invoice.issue();
        drawer.count();
        customer.name();
        promotions.price();
        tables.table();
        ticket.print();
    }
}
