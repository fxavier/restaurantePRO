package com.xavier.archfixture.forbidden.settlement;

import com.xavier.archfixture.forbidden.inventory.StockLedger;

/**
 * Test fixture only: settlement → inventory, forbidden by D99 (spec §8.4: a refund or a financial reversal never
 * restores stock).
 */
public class StockRefund {

    private final StockLedger stock = new StockLedger();

    public void refund() {
        stock.post();
    }
}
