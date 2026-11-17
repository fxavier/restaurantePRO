package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * More than ten enrolment attempts in a minute from one source address (D182 as amended); answered 429
 * {@code ENROLMENT_THROTTLED} by the enrolment route.
 */
public class EnrolmentThrottledException extends BusinessRuleException {

    public static final String CODE = "ENROLMENT_THROTTLED";

    public EnrolmentThrottledException() {
        super(CODE, "Too many enrolment attempts; try again in a minute.");
    }
}
