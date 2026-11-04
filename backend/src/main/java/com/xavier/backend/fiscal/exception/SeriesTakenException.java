package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The store already has a series with that code, or already has an active series of that type. 409. */
public class SeriesTakenException extends ConflictException {

    public SeriesTakenException(String message) {
        super(message);
    }
}
