package com.xavier.backend.orders.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The family of a confirmed line's item, from the catalog release the line was priced from (D84, D111, D112): a
 * reward and a loyalty program may be scoped by family, and a later release never changes it. One instance reads each
 * release once; {@link #familyOf} is for a single line.
 */
@Component
public class LineFamilies {

    private final CatalogApi catalog;

    LineFamilies(CatalogApi catalog) {
        this.catalog = catalog;
    }

    /** {@code null} when the release has no such item (never for a line confirmed from it). */
    public UUID familyOf(UUID storeId, UUID releaseId, UUID itemId) {
        return resolver(storeId).familyOf(releaseId, itemId);
    }

    /** A resolver that reads each release of the store at most once, for the lines of one order. */
    public Resolver resolver(UUID storeId) {
        return new Resolver(storeId);
    }

    public final class Resolver {

        private final UUID storeId;
        private final Map<UUID, CatalogSnapshot> releases = new HashMap<>();

        private Resolver(UUID storeId) {
            this.storeId = storeId;
        }

        public UUID familyOf(UUID releaseId, UUID itemId) {
            CatalogSnapshot release = releases.computeIfAbsent(releaseId, id -> catalog.snapshot(storeId, id));
            return release.items().stream().filter(item -> item.id().equals(itemId)).findFirst()
                    .map(CatalogSnapshot.Item::familyId).orElse(null);
        }
    }
}
