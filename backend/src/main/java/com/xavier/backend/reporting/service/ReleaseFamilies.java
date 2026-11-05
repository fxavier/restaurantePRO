package com.xavier.backend.reporting.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The family code of an item as a catalog release had it (D84): historically correct, because a line keeps the
 * release it was priced from. A published release never changes, so its item → family map is parsed once and kept.
 */
@Component
class ReleaseFamilies {

    private final CatalogApi catalog;
    private final Map<UUID, Map<UUID, String>> byRelease = new ConcurrentHashMap<>();

    ReleaseFamilies(CatalogApi catalog) {
        this.catalog = catalog;
    }

    /** @return the family code, or {@code null} when the line has no release or the item is not in it */
    String familyOf(UUID storeId, UUID releaseId, UUID itemId) {
        if (releaseId == null || itemId == null) {
            return null;
        }
        return byRelease.computeIfAbsent(releaseId, id -> familiesOf(catalog.snapshot(storeId, id))).get(itemId);
    }

    private static Map<UUID, String> familiesOf(CatalogSnapshot snapshot) {
        Map<UUID, String> familyCodes = snapshot.families().stream()
                .collect(Collectors.toMap(CatalogSnapshot.Family::id, CatalogSnapshot.Family::code));
        return snapshot.items().stream().filter(item -> familyCodes.containsKey(item.familyId()))
                .collect(Collectors.toUnmodifiableMap(CatalogSnapshot.Item::id,
                        item -> familyCodes.get(item.familyId())));
    }
}
