package com.xavier.archfixture.forbidden.reporting;

import com.xavier.archfixture.forbidden.audit.AuditTrail;
import com.xavier.archfixture.forbidden.orders.OrdersFacade;

/** Test fixture only: reporting calling facade writes (forbidden, D65) next to allowed read-only calls. */
public class SalesReport {
    private final OrdersFacade orders = new OrdersFacade();
    private final OrdersFacade.CountingFacade counting = new OrdersFacade.CountingFacade();
    private final AuditTrail audit = new AuditTrail();

    public String build() {
        String read = orders.find();
        long rows = counting.count();
        orders.close();
        orders.reopen();
        audit.record();
        return read + rows;
    }
}
