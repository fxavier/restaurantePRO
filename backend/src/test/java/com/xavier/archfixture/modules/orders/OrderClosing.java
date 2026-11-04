package com.xavier.archfixture.modules.orders;

import com.xavier.archfixture.modules.settlement.SettlementApi;

/** Test fixture only: the illegal dependency spec §6 forbids (orders must not depend on settlement). */
public class OrderClosing {

    private final SettlementApi settlement = new SettlementApi();

    public void close() {
        settlement.settle();
    }
}
