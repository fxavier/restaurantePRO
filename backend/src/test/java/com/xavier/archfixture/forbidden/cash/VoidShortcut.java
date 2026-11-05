package com.xavier.archfixture.forbidden.cash;

import com.xavier.archfixture.forbidden.orders.OrdersApi;

/** Test fixture only: a module other than settlement voiding past the PAYMENT lock (forbidden, D71). */
public class VoidShortcut {

    public void voidAnyway(OrdersApi orders) {
        orders.billingView();
        orders.voidLine();
    }
}
