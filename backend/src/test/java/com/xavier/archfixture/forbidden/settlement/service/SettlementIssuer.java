package com.xavier.archfixture.forbidden.settlement.service;

import com.xavier.archfixture.forbidden.fiscal.FiscalApi;

/** Test fixture only: the one allowed settlement caller of {@code FiscalApi.issue} (D97). */
public class SettlementIssuer {

    public void issue(FiscalApi fiscal) {
        fiscal.recordPayments();
        fiscal.issue();
    }
}
