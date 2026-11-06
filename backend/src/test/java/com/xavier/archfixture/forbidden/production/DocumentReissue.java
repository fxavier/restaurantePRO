package com.xavier.archfixture.forbidden.production;

import com.xavier.archfixture.forbidden.fiscal.FiscalApi;

/** Test fixture only: a class outside settlement issuing and recording a snapshot itself, forbidden by D97. */
public class DocumentReissue {

    public void reissue(FiscalApi fiscal) {
        fiscal.recordPayments();
        fiscal.issue();
    }
}
