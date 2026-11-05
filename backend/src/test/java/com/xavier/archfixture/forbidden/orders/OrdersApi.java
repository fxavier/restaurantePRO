package com.xavier.archfixture.forbidden.orders;

/** Test fixture only: the orders facade whose {@code voidLine} bypasses the PAYMENT lock (D71). */
public class OrdersApi {

    public void voidLine() {
        // fixture
    }

    public void billingView() {
        // fixture: any other method may be called from anywhere
    }
}
