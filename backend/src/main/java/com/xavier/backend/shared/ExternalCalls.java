package com.xavier.backend.shared;

import org.springframework.transaction.support.ResourceHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guard for calls to external systems (payment provider, remote fiscal authority; D48). Such a call must never run
 * while a database transaction or a pooled connection is held: it would keep row locks and a connection for the
 * provider's latency, and a rollback cannot undo what the provider did. External calls follow "persist, then
 * call, then record" (a domain pattern, not a helper): every adapter calls {@link #requireNoTransaction()} first.
 */
public final class ExternalCalls {

    private ExternalCalls() {
    }

    /**
     * @throws IllegalStateException when a transaction is active on this thread, or a transactional resource
     *                               (a JDBC connection, an {@code EntityManager}) is bound to it
     */
    public static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External call inside a database transaction: commit first, then call "
                    + "(D48 persist, then call, then record)");
        }
        boolean resourceBound = TransactionSynchronizationManager.getResourceMap().values().stream()
                .anyMatch(ResourceHolder.class::isInstance);
        if (resourceBound) {
            throw new IllegalStateException("External call while a database connection is bound to the thread "
                    + "(D48): release it before calling");
        }
    }
}
