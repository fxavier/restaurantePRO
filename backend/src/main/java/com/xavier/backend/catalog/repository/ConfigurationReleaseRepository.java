package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.ReleaseStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConfigurationReleaseRepository extends JpaRepository<ConfigurationRelease, UUID> {

    Optional<ConfigurationRelease> findByStoreIdAndStatus(UUID storeId, ReleaseStatus status);

    Page<ConfigurationRelease> findAllByStoreId(UUID storeId, Pageable pageable);

    @Query("select coalesce(max(r.catalogVersion), 0) from ConfigurationRelease r where r.storeId = :storeId")
    long maxCatalogVersion(UUID storeId);
}
