package com.xavier.backend.reporting.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The family code of an item as a catalog release had it (D84): historically correct, because a line keeps the
 * release it was priced from. A published release never changes, so its item → family map is parsed once and kept,
 * per tenant: the key is {@code (tenantId, releaseId)}, so a release id never serves another tenant's families
 * (security rule 7).
 */
@Component
class ReleaseFamilies {

    private record Key(UUID tenantId, UUID releaseId) {
    }

    private final CatalogApi catalog;
    private final TenantScopes tenants;
    // ponytail: never cleared; one entry per tenant per release (a published release never changes). Upgrade path: a
    // bounded LRU when a tenant's release history makes the map worth bounding.
    private final Map<Key, Map<UUID, String>> byRelease = new ConcurrentHashMap<>();

    ReleaseFamilies(CatalogApi catalog, TenantScopes tenants) {
        this.catalog = catalog;
        this.tenants = tenants;
    }

    /** @return the family code, or {@code null} when the line has no release or the item is not in it */
    String familyOf(UUID storeId, UUID releaseId, UUID itemId) {
        if (releaseId == null || itemId == null) {
            return null;
        }
        return byRelease.computeIfAbsent(new Key(tenants.requireTenant(), releaseId),
                key -> familiesOf(catalog.snapshot(storeId, releaseId))).get(itemId);
    }

    private static Map<UUID, String> familiesOf(CatalogSnapshot snapshot) {
        Map<UUID, String> familyCodes = snapshot.families().stream()
                .collect(Collectors.toMap(CatalogSnapshot.Family::id, CatalogSnapshot.Family::code));
        return snapshot.items().stream().filter(item -> familyCodes.containsKey(item.familyId()))
                .collect(Collectors.toUnmodifiableMap(CatalogSnapshot.Item::id,
                        item -> familyCodes.get(item.familyId())));
    }
}
