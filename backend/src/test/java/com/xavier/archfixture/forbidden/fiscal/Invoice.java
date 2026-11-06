package com.xavier.archfixture.forbidden.fiscal;

import com.xavier.archfixture.forbidden.customers.Customer;

/** Test fixture only: fiscal → customers, forbidden by D65 (the buyer is a snapshot passed by settlement). */
public class Invoice {
    private final Customer buyer = new Customer();

    public String issue() {
        return buyer.name();
    }
}
