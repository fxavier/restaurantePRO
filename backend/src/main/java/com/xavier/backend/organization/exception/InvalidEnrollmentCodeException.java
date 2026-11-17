package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * The enrolment code is unknown, used, expired or burned, or its device cannot enrol (D182): one answer for every case,
 * so the answer reveals nothing about which codes exist. Answered 400 {@code ENROLMENT_CODE_INVALID} by the enrolment
 * route (spec error codes).
 */
public class InvalidEnrollmentCodeException extends BusinessRuleException {

    public static final String CODE = "ENROLMENT_CODE_INVALID";

    public InvalidEnrollmentCodeException() {
        super(CODE, "The enrolment code is invalid or has expired.");
    }
}
