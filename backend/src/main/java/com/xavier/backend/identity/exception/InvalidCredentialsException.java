package com.xavier.backend.identity.exception;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * A login failed. One generic answer (401) whatever the reason (unknown user, wrong secret, locked, inactive,
 * wrong terminal), so a caller cannot probe which accounts exist; the reason is logged at WARN instead.
 */
public class InvalidCredentialsException extends BadCredentialsException {

    public InvalidCredentialsException() {
        super("Invalid credentials.");
    }
}
