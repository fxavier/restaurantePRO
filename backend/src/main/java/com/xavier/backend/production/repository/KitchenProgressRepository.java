package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.KitchenProgress;
import com.xavier.backend.production.model.KitchenStatus;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenProgressRepository extends JpaRepository<KitchenProgress, UUID> {

    Page<KitchenProgress> findByStoreIdAndStatusIn(UUID storeId, Collection<KitchenStatus> statuses,
                                                   Pageable pageable);

    Page<KitchenProgress> findByStoreIdAndDestinationAndStatusIn(UUID storeId, String destination,
                                                                 Collection<KitchenStatus> statuses,
                                                                 Pageable pageable);
}
