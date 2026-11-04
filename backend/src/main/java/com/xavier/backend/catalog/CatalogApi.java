package com.xavier.backend.catalog;

import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.NoPublishedCatalogException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * What other modules may ask the catalog (spec §8.2): the frozen catalog of a store as a typed
 * {@link CatalogSnapshot}, parsed from the stored jsonb once per call. Orders (S13) snapshot name, price and tax
 * from it at line confirmation (D58). Read-only and without authorization: callers authorize their own use case.
 */
@Service
@Transactional(readOnly = true)
public class CatalogApi {

    private final ConfigurationReleaseRepository releases;
    private final JsonMapper json;

    CatalogApi(ConfigurationReleaseRepository releases, JsonMapper json) {
        this.releases = releases;
        this.json = json;
    }

    /**
     * The catalog the store sells from now.
     *
     * @throws NoPublishedCatalogException (404) when the store has never published
     */
    public CatalogSnapshot currentSnapshot(UUID storeId) {
        return parse(releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new NoPublishedCatalogException(storeId)));
    }

    /**
     * A frozen release of the store, current or retired: an order line confirmed under it keeps reading it.
     *
     * @throws CatalogEntryNotFoundException (404) when the release does not exist, belongs to another store, or
     *                                       was never published
     */
    public CatalogSnapshot snapshot(UUID storeId, UUID releaseId) {
        return parse(releases.findById(releaseId)
                .filter(release -> release.getStoreId().equals(storeId) && release.getSnapshot() != null)
                .orElseThrow(() -> new CatalogEntryNotFoundException("Release", releaseId)));
    }

    private CatalogSnapshot parse(ConfigurationRelease release) {
        return json.readValue(release.getSnapshot(), CatalogSnapshot.class);
    }
}
