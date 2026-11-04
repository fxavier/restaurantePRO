package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConfigurationReleaseRepository extends JpaRepository<ConfigurationRelease, UUID> {

    Optional<ConfigurationRelease> findByStoreIdAndStatus(UUID storeId, ReleaseStatus status);

    List<ConfigurationRelease> findAllByStoreIdOrderByCreatedAtDesc(UUID storeId);

    @Query("select coalesce(max(r.catalogVersion), 0) from ConfigurationRelease r where r.storeId = :storeId")
    long maxCatalogVersion(UUID storeId);
}
