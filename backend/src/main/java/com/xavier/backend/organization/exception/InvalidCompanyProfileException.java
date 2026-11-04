package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A company profile value is not acceptable (missing, or not a usable ISO country/currency code). 422. */
public class InvalidCompanyProfileException extends BusinessRuleException {

    public InvalidCompanyProfileException(String message) {
        super(message);
    }
}
