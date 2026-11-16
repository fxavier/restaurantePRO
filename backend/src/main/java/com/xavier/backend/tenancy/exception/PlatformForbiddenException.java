package com.xavier.backend.tenancy.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A platform write was attempted from a tenant-bound transaction (TN004): only an unbound platform request (an
 * ops-realm operator, S72's {@code /platform/**} rule) may make one. 403; nothing changed.
 */
public class PlatformForbiddenException extends AccessDeniedException {

    public PlatformForbiddenException() {
        super("Only a platform operator may do this.");
    }
}
