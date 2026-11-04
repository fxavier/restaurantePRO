package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.NoPublishedCatalogException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.model.dto.PosCatalogResponse;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the POS sells from: the current published snapshot of the session's store ({@code CATALOG_VIEW} there,
 * through {@code requirePos}, D39).
 */
@Service
public class PublishedCatalogService {

    private final ConfigurationReleaseRepository releases;
    private final AccessPolicy accessPolicy;
    private final JsonMapper json;

    PublishedCatalogService(ConfigurationReleaseRepository releases, AccessPolicy accessPolicy, JsonMapper json) {
        this.releases = releases;
        this.accessPolicy = accessPolicy;
        this.json = json;
    }

    /**
     * @param requestedStoreId optional ({@code null}: the session's store); naming another store is refused
     * @throws AccessDeniedException (403) outside a POS session, without {@code CATALOG_VIEW} in the session's
     *                               store, or when {@code requestedStoreId} is another store
     */
    @Transactional(readOnly = true)
    public PosCatalogResponse current(UUID requestedStoreId) {
        UUID storeId = accessPolicy.requirePos(Permission.CATALOG_VIEW, requestedStoreId).storeId();
        ConfigurationRelease release = releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new NoPublishedCatalogException(storeId));
        return new PosCatalogResponse(release.getId(), storeId, release.getCatalogVersion(), release.getEffectiveAt(),
                json.readTree(release.getSnapshot()));
    }
}
