package com.xavier.archfixture.forbidden.inventory;

import org.springframework.stereotype.Service;

/** Test fixture only: inventory's own service, which only reporting may reach from outside (D99). */
@Service
public class StockLedger {

    /** A write of inventory itself: not a facade of another module's facts. */
    public void post() {
        // fixture
    }
}
