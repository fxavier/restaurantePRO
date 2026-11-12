package com.xavier.archfixture.forbidden.shared.ops;

import com.xavier.archfixture.forbidden.orders.OrderTotals;

/** Test fixture only: shared.ops reaching into a module, forbidden by D137 (shared depends on no module). */
public class LeakyAlertSource {

    public void count() {
        new OrderTotals().close();
    }
}
