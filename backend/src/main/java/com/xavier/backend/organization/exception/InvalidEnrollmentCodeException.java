package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * The enrollment code is unknown, expired, already used, or its terminal cannot enroll. One message for
 * every case, so the answer reveals nothing about which codes exist. 422.
 */
public class InvalidEnrollmentCodeException extends BusinessRuleException {

    public InvalidEnrollmentCodeException() {
        super("The enrollment code is invalid or has expired.");
    }
}
