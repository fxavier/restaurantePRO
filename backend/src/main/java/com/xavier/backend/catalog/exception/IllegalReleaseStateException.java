package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The release is not in a state that allows this transition (spec §8.3), or another publication won. 409. */
public class IllegalReleaseStateException extends ConflictException {

    public IllegalReleaseStateException(String message) {
        super(message);
    }
}
