package com.xavier.backend.catalog.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * The release is not in a state that allows this transition (spec §8.3), or another publication won. 409 with code
 * {@value #CODE}.
 */
public class IllegalReleaseStateException extends ConflictException {

    public static final String CODE = "ILLEGAL_RELEASE_STATE";

    public IllegalReleaseStateException(String message) {
        super(CODE, message);
    }
}
