package com.xavier.backend.dining.service;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The client edits what it read: an update carrying an older version than the stored one is stale (D17, 409).
 * A concurrent writer that passes this check is still stopped by {@code @Version} at flush.
 */
final class OptimisticLocking {

    private OptimisticLocking() {
    }

    static void requireVersion(Class<?> type, Object id, long stored, long sentByClient) {
        if (stored != sentByClient) {
            throw new ObjectOptimisticLockingFailureException(type, id);
        }
    }
}
