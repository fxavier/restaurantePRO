package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.NoPublishedCatalogException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * What the POS sells from: the current published snapshot of the session's store ({@code CATALOG_VIEW} there,
 * through {@code requirePos}, D39).
 */
@Service
public class PublishedCatalogService {

    /** The store's published release, its snapshot as stored ({@code jsonb} text), unparsed (S77). */
    public record Published(UUID releaseId, UUID storeId, long version, Instant effectiveAt, String snapshot) {
    }

    private final ConfigurationReleaseRepository releases;
    private final AccessPolicy accessPolicy;

    PublishedCatalogService(ConfigurationReleaseRepository releases, AccessPolicy accessPolicy) {
        this.releases = releases;
        this.accessPolicy = accessPolicy;
    }

    /**
     * @param requestedStoreId optional ({@code null}: the session's store); naming another store is refused
     * @throws AccessDeniedException (403) outside a POS session, without {@code CATALOG_VIEW} in the session's
     *                               store, or when {@code requestedStoreId} is another store
     * @return the snapshot unparsed: the caller parses it after this transaction ends (S77, a shorter hold)
     */
    @Transactional(readOnly = true)
    public Published current(UUID requestedStoreId) {
        UUID storeId = accessPolicy.requirePos(Permission.CATALOG_VIEW, requestedStoreId).storeId();
        ConfigurationRelease release = releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new NoPublishedCatalogException(storeId));
        return new Published(release.getId(), storeId, release.getCatalogVersion(), release.getEffectiveAt(),
                release.getSnapshot());
    }
}
