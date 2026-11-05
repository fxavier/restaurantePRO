package com.xavier.archfixture.forbidden.settlement;

import com.xavier.archfixture.forbidden.orders.OrdersApi;

/** Test fixture only: settlement's coordinated void, the one allowed caller of {@code OrdersApi.voidLine} (D71). */
public class SettledVoids {

    public void voidWithinHeadroom(OrdersApi orders) {
        orders.voidLine();
    }
}
