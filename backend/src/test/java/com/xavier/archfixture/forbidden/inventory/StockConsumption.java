package com.xavier.archfixture.forbidden.inventory;

import com.xavier.archfixture.forbidden.audit.AuditTrail;
import com.xavier.archfixture.forbidden.orders.OrdersFacade;

/**
 * Test fixture only: inventory calling an orders facade write (forbidden, D99) next to allowed calls: read-only
 * facade methods, its own service and the audit trail.
 */
public class StockConsumption {
    private final OrdersFacade orders = new OrdersFacade();
    private final OrdersFacade.CountingFacade counting = new OrdersFacade.CountingFacade();
    private final StockLedger ledger = new StockLedger();
    private final AuditTrail audit = new AuditTrail();

    public String consume() {
        String round = orders.find();
        long rows = counting.count();
        ledger.post();
        audit.record();
        orders.close();
        return round + rows;
    }
}
