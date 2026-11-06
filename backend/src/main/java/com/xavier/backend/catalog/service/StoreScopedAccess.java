package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.repository.StoreScope;
import com.xavier.backend.organization.OrganizationApi;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code CATALOG_MANAGE} for configuration that belongs to one store or, with no store, to every store (quick pages,
 * automatic items): the store's grant, or an installation-wide one for every store. For an entry addressed by id the
 * store is read alone first (D42: 404, then 403).
 */
@Component
class StoreScopedAccess {

    private final AccessPolicy accessPolicy;
    private final OrganizationApi organization;

    StoreScopedAccess(AccessPolicy accessPolicy, OrganizationApi organization) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
    }

    /** Before creating in {@code storeId} ({@code null} = every store), which must exist (422). */
    void requireForNew(UUID storeId) {
        require(storeId);
        if (storeId != null && !organization.storeExists(storeId)) {
            throw new InvalidCatalogEntryException("Store " + storeId + " does not exist");
        }
    }

    /** @throws CatalogEntryNotFoundException (404) when {@code scope} is empty, before any permission check */
    void requireExisting(Optional<StoreScope> scope, String type, UUID id) {
        require(scope.orElseThrow(() -> new CatalogEntryNotFoundException(type, id)).getStoreId());
    }

    void require(UUID storeId) {
        if (storeId == null) {
            accessPolicy.require(Permission.CATALOG_MANAGE);
        } else {
            accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        }
    }
}
