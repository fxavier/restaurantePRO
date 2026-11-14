package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.repository.EntryStore;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code CATALOG_MANAGE} for configuration that belongs to one store or, with no store, to every store (quick pages,
 * automatic items): the store's grant, or an installation-wide one for every store. For an entry addressed by id the
 * store is read alone first (D42: 404, then 403).
 *
 * <p>Under row-level security (D168) an entry of every store is visible in every scope, and one of another store is
 * hidden: its store is then probed through {@code shared.store_of}, which sees every store, so it is refused (403),
 * never mistaken for absent. {@code store_of} answers NULL both for an absent row and an every-store one, so the
 * visible read comes first.
 */
@Component
class StoreScopedAccess {

    private final AccessPolicy accessPolicy;
    private final OrganizationApi organization;
    private final StoreProbe probe;

    StoreScopedAccess(AccessPolicy accessPolicy, OrganizationApi organization, StoreProbe probe) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
        this.probe = probe;
    }

    /** Before creating in {@code storeId} ({@code null} = every store), which must exist (422). */
    void requireForNew(UUID storeId) {
        require(storeId);
        if (storeId != null && !organization.storeExists(storeId)) {
            throw new InvalidCatalogEntryException("Store " + storeId + " does not exist");
        }
    }

    /**
     * @param visible the entry's store as the caller's scope reads it (empty when absent or of another store)
     * @param table   the entry's table, for the probe of another store's entry
     * @throws CatalogEntryNotFoundException (404) when the entry exists in no store, before any permission check
     */
    void requireExisting(Optional<EntryStore> visible, String table, String type, UUID id) {
        if (visible.isPresent()) {
            require(visible.get().storeId());
            return;
        }
        require(probe.storeOf(table, id).orElseThrow(() -> new CatalogEntryNotFoundException(type, id)));
    }

    void require(UUID storeId) {
        if (storeId == null) {
            accessPolicy.require(Permission.CATALOG_MANAGE);
        } else {
            accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
        }
    }
}
