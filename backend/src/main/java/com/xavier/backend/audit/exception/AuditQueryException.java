package com.xavier.backend.audit.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;

/** An audit read or export that is too large to serve (D82). */
public class AuditQueryException extends BusinessRuleException {

    private AuditQueryException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties);
    }

    public static AuditQueryException rangeTooLarge(long maxDays) {
        return new AuditQueryException("RANGE_TOO_LARGE",
                "The range is longer than " + maxDays + " days: narrow it.", Map.of("maxDays", maxDays));
    }

    public static AuditQueryException exportTooLarge(long rows, long maxRows) {
        return new AuditQueryException("EXPORT_TOO_LARGE",
                "The export would have " + rows + " rows, more than " + maxRows + ": narrow the filters.",
                Map.of("rows", rows, "maxRows", maxRows));
    }
}
