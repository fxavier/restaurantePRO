package com.xavier.backend.dining.service;

import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.organization.OrganizationApi;
import java.util.UUID;

/**
 * An unknown store is 404, like organization's own terminal registration: the store is the resource the
 * request is scoped to (as in {@code /stores/{id}/...}), not a reference inside the payload. It runs after
 * the permission check, so only an actor allowed in that store id learns whether it exists.
 */
final class StoreChecks {

    private StoreChecks() {
    }

    static void requireExisting(OrganizationApi organization, UUID storeId) {
        if (!organization.storeExists(storeId)) {
            throw new DiningNotFoundException("Store", storeId);
        }
    }
}
