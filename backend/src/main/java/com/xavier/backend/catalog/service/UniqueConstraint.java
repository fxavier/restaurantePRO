package com.xavier.backend.catalog.service;

import java.util.Set;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Duplicates are decided by the database: saving and flushing at once turns a violated unique constraint
 * (also one lost in a race between two requests) into its business exception (D22: 409, never 500). Only the
 * named constraints are translated; any other integrity error surfaces unchanged.
 */
final class UniqueConstraint {

    private UniqueConstraint() {
    }

    static <T> T saveAndFlush(JpaRepository<T, ?> repository, T entity, Supplier<? extends RuntimeException> onDuplicate,
                              String... constraintNames) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isViolated(e, Set.of(constraintNames))) {
                throw onDuplicate.get();
            }
            throw e;
        }
    }

    static boolean isViolated(DataIntegrityViolationException e, Set<String> constraintNames) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName() != null
                        && constraintNames.stream().anyMatch(violation.getConstraintName()::equalsIgnoreCase);
            }
        }
        return false;
    }
}
