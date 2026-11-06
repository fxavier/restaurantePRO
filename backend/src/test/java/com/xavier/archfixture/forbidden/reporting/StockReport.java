package com.xavier.archfixture.forbidden.reporting;

import com.xavier.archfixture.forbidden.inventory.StockLedger;

/** Test fixture only: reporting → inventory, the one edge into inventory D99 allows. */
public class StockReport {
    private final StockLedger stock = new StockLedger();

    public StockLedger source() {
        return stock;
    }
}
