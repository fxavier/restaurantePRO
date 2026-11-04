package com.xavier.backend.shared.persistence;

import java.util.Arrays;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Duplicates are decided by the database (D22, D43): a service may check uniqueness first for a clear answer, but
 * two concurrent requests can both pass that check, and then the unique constraint decides. Saving and flushing at
 * once turns a violated <em>named</em> constraint (also one lost in a race) into its business exception: 409, never
 * 500. Any other integrity error surfaces unchanged.
 *
 * <pre>{@code
 * Store saved = UniqueConstraints.saveAndFlush(stores, store,
 *         () -> new StoreCodeTakenException(store.getCode()), "store_code_uk");
 * }</pre>
 */
public final class UniqueConstraints {

    private UniqueConstraints() {
    }

    /**
     * {@code repository.saveAndFlush(entity)}; a violation of one of {@code constraintNames} becomes
     * {@code onDuplicate.get()}.
     */
    public static <T> T saveAndFlush(JpaRepository<T, ?> repository, T entity,
                                     Supplier<? extends RuntimeException> onDuplicate, String... constraintNames) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isViolated(e, constraintNames)) {
                throw onDuplicate.get();
            }
            throw e;
        }
    }

    /** True when {@code e} was caused by one of {@code constraintNames} (case-insensitive). */
    public static boolean isViolated(DataIntegrityViolationException e, String... constraintNames) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String violated = violation.getConstraintName();
                return violated != null && Arrays.stream(constraintNames).anyMatch(violated::equalsIgnoreCase);
            }
        }
        return false;
    }
}
