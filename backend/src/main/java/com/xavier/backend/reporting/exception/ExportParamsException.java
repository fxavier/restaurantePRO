package com.xavier.backend.reporting.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;

/** An export whose parameters are not those of its report (missing, unknown or malformed). 422. */
public class ExportParamsException extends BusinessRuleException {

    public ExportParamsException(String param) {
        super("INVALID_EXPORT_PARAMS", "The export parameter '" + param + "' is missing or not valid for the report.",
                Map.of("param", param));
    }
}
