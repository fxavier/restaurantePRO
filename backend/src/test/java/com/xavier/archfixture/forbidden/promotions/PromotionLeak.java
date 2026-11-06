package com.xavier.archfixture.forbidden.promotions;

import com.xavier.archfixture.forbidden.cash.CashDrawer;
import com.xavier.archfixture.forbidden.dining.TableMap;
import com.xavier.archfixture.forbidden.fiscal.Invoice;
import com.xavier.archfixture.forbidden.inventory.StockLedger;
import com.xavier.archfixture.forbidden.orders.OrderTotals;
import com.xavier.archfixture.forbidden.production.Ticket;
import com.xavier.archfixture.forbidden.reporting.SalesReport;
import com.xavier.archfixture.forbidden.settlement.Tender;

/**
 * Test fixture only: promotions → orders, settlement, fiscal, cash, production, inventory, dining and reporting, all
 * forbidden by D99.
 */
public class PromotionLeak {
    private final OrderTotals orders = new OrderTotals();
    private final Tender tender = new Tender();
    private final Invoice invoice = new Invoice();
    private final CashDrawer drawer = new CashDrawer();
    private final Ticket ticket = new Ticket();
    private final StockLedger stock = new StockLedger();
    private final TableMap tables = new TableMap();
    private final SalesReport report = new SalesReport();

    public void earn() {
        orders.close();
        tender.settle();
        invoice.issue();
        drawer.count();
        ticket.print();
        stock.post();
        tables.table();
        report.build();
    }
}
