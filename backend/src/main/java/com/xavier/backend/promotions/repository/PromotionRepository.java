package com.xavier.backend.promotions.repository;

import com.xavier.backend.promotions.model.Promotion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    /** The ACTIVE promotions assigned to {@code storeId}, ordered by code: what its next release freezes. */
    @Query("select p from Promotion p where p.active = true and :storeId member of p.storeIds order by p.code")
    List<Promotion> findActiveForStore(UUID storeId);
}
