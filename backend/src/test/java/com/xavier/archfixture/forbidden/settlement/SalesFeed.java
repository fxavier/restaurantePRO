package com.xavier.archfixture.forbidden.settlement;

import com.xavier.archfixture.forbidden.reporting.SalesReport;

/** Test fixture only: settlement → reporting, forbidden by D65 (nothing depends on reporting). */
public class SalesFeed {
    public String feed() {
        return new SalesReport().build();
    }
}
