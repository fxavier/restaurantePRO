package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;

/** A support grant lasts more than nothing and at most 24 hours (P36, D83; 422, code {@value #CODE}). */
public class SupportGrantTooLongException extends BusinessRuleException {

    public static final String CODE = "SUPPORT_GRANT_DURATION";

    public SupportGrantTooLongException(long maxMinutes) {
        super(CODE, "A support grant lasts between 1 minute and 24 hours.", Map.of("maxMinutes", maxMinutes));
    }
}
