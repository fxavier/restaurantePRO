package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The command does not fit the series' state (e.g. deactivating an inactive series). 409. */
public class IllegalSeriesStateException extends ConflictException {

    public IllegalSeriesStateException(String message) {
        super(message);
    }
}
