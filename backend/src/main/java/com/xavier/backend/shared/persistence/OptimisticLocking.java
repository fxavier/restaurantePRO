package com.xavier.backend.shared.persistence;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The client edits what it read (D17, D43): an update carrying another version than the stored one is stale and
 * gives 409. A concurrent writer that passes this check is still stopped by {@code @Version} at flush.
 */
public final class OptimisticLocking {

    private OptimisticLocking() {
    }

    public static void requireVersion(Class<?> type, Object id, long stored, long sentByClient) {
        if (stored != sentByClient) {
            throw new ObjectOptimisticLockingFailureException(type, id);
        }
    }
}
