package com.xavier.archfixture.forbidden.cash;

import com.xavier.archfixture.forbidden.orders.OrderTotals;
import com.xavier.archfixture.forbidden.settlement.Tender;

/** Test fixture only: cash → orders and cash → settlement, both forbidden by D35. */
public class CashDrawer {

    private final OrderTotals orders = new OrderTotals();
    private final Tender tender = new Tender();

    public void count() {
        orders.close();
        tender.settle();
    }
}
