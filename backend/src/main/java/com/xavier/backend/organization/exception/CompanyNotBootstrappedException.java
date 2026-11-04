package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** The installation has no company yet: bootstrap settings were missing at startup (D30). 404. */
public class CompanyNotBootstrappedException extends NotFoundException {

    public CompanyNotBootstrappedException() {
        super("The company has not been bootstrapped. Set restaurantpro.bootstrap.company.* and restart.");
    }
}
