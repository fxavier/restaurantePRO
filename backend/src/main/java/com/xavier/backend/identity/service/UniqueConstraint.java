package com.xavier.backend.identity.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Turns a lost race on a unique constraint into its business exception (D22: 409, not 500). The service checks
 * first for a clear answer; two concurrent requests can both pass that check, and then the database decides.
 * Only the named constraint is translated; other integrity errors surface. (Same pattern as organization's.)
 */
final class UniqueConstraint {

    private UniqueConstraint() {
    }

    static boolean isViolated(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
