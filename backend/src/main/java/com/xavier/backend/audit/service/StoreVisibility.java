package com.xavier.backend.audit.service;

import java.util.Set;
import java.util.UUID;

/**
 * Whose audit rows a caller reads (D82): {@code allStores} for an installation-wide auditor (every store and the
 * store-less rows), otherwise only the rows of {@code stores}.
 */
record StoreVisibility(boolean allStores, Set<UUID> stores) {

    static final StoreVisibility EVERYWHERE = new StoreVisibility(true, Set.of());

    StoreVisibility {
        stores = Set.copyOf(stores);
    }

    static StoreVisibility only(Set<UUID> stores) {
        return new StoreVisibility(false, stores);
    }
}
