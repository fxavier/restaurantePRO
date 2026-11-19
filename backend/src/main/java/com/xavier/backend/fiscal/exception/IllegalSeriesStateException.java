package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The command does not fit the series' state (e.g. deactivating an inactive series). 409 with code {@value #CODE}. */
public class IllegalSeriesStateException extends ConflictException {

    public static final String CODE = "ILLEGAL_SERIES_STATE";

    public IllegalSeriesStateException(String message) {
        super(CODE, message);
    }
}
