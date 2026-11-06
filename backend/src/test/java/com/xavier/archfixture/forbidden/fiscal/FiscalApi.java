package com.xavier.archfixture.forbidden.fiscal;

/** Test fixture only: the fiscal facade whose {@code issue} only settlement's SettlementIssuer calls (D97). */
public class FiscalApi {

    public void issue() {
        // fixture
    }

    public void recordPayments() {
        // fixture
    }

    public void coverage() {
        // fixture: any other method may be called from anywhere in settlement
    }
}
