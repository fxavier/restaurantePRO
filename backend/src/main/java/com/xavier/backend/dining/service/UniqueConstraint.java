package com.xavier.backend.dining.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Turns a lost race on a unique constraint into its business exception. The service checks uniqueness first
 * for a clear answer; two concurrent requests can both pass that check, and then the database constraint
 * decides (D22: 409, not 500). Only the named constraint is translated; other integrity errors surface.
 * Same pattern as organization's (module-internal there, so copied rather than shared).
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
