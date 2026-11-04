package com.xavier.backend.cash.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

/**
 * D42 for POS commands: the POS session fixes the store, and a register, session, day or closing addressed in the
 * request must belong to it (else 403, logged like every denial).
 */
final class StoreScope {

    private static final Logger log = LoggerFactory.getLogger(StoreScope.class);

    private StoreScope() {
    }

    static void requireSameStore(UUID sessionStore, UUID resourceStore, String entityType, UUID resourceId) {
        if (!sessionStore.equals(resourceStore)) {
            log.warn("Access denied: {} {} belongs to store {}, the POS session to store {}", entityType, resourceId,
                    resourceStore, sessionStore);
            throw new AccessDeniedException(entityType + " " + resourceId + " is not in this store");
        }
    }
}
