package com.xavier.backend.reporting.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.LocalDate;
import java.util.Map;

/** A report or rebuild range of business dates that cannot be served: reversed, or longer than the maximum. 422. */
public class ReportRangeException extends BusinessRuleException {

    private ReportRangeException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties);
    }

    public static ReportRangeException reversed(LocalDate from, LocalDate to) {
        return new ReportRangeException("RANGE_REVERSED", "The range starts on " + from + ", after its end " + to
                + ".", Map.of());
    }

    public static ReportRangeException tooLarge(long maxDays) {
        return new ReportRangeException("RANGE_TOO_LARGE",
                "The range is longer than " + maxDays + " days: narrow it.", Map.of("maxDays", maxDays));
    }
}
