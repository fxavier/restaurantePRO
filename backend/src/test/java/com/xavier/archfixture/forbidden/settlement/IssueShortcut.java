package com.xavier.archfixture.forbidden.settlement;

import com.xavier.archfixture.forbidden.fiscal.FiscalApi;

/** Test fixture only: a settlement class issuing without the payment snapshot refresh, forbidden by D97. */
public class IssueShortcut {

    public void issueWithoutSnapshot(FiscalApi fiscal) {
        fiscal.coverage();
        fiscal.issue();
    }
}
