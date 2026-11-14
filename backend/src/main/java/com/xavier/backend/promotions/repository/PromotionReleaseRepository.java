package com.xavier.backend.promotions.repository;

import com.xavier.backend.promotions.model.PromotionRelease;
import com.xavier.backend.promotions.model.ReleaseStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PromotionReleaseRepository extends JpaRepository<PromotionRelease, UUID> {

    Optional<PromotionRelease> findByStoreIdAndStatus(UUID storeId, ReleaseStatus status);

    Page<PromotionRelease> findAllByStoreId(UUID storeId, Pageable pageable);


    @Query("select coalesce(max(r.versionNo), 0) from PromotionRelease r where r.storeId = :storeId")
    long maxVersionNo(UUID storeId);
}
