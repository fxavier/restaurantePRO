package com.xavier.archfixture.forbidden.orders;

import com.xavier.archfixture.forbidden.inventory.StockLedger;

/** Test fixture only: orders → inventory, forbidden by D99 (stock consumes order facts through events). */
public class StockShortcut {
    private final StockLedger stock = new StockLedger();

    public void confirm() {
        stock.post();
    }
}
