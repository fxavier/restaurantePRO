package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.Duration;

/**
 * More than ten enrolment attempts in a minute from one source address (D182 as amended); answered 429
 * {@code ENROLMENT_THROTTLED} by the enrolment route, with {@code Retry-After} in seconds (FE-F12).
 */
public class EnrolmentThrottledException extends BusinessRuleException {

    public static final String CODE = "ENROLMENT_THROTTLED";

    private final Duration retryAfter;

    /** @param retryAfter the throttle's window: waiting that long always lets the next attempt through */
    public EnrolmentThrottledException(Duration retryAfter) {
        super(CODE, "Too many enrolment attempts; try again in a minute.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
