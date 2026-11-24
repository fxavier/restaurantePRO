package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.KitchenProgress;
import com.xavier.backend.production.model.KitchenStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KitchenProgressRepository extends JpaRepository<KitchenProgress, UUID> {

    Page<KitchenProgress> findByStoreIdAndStatusIn(UUID storeId, Collection<KitchenStatus> statuses,
                                                   Pageable pageable);

    /**
     * The rounds of {@code storeId} with a ticket in {@code statuses} queued before {@code before}, each round once: a
     * round sent to two stations is one batch (the tickets' {@code sourceId}) with a ticket on each. Index
     * {@code kitchen_progress_store_status_ix}.
     */
    @Query("""
            select count(distinct t.sourceId) from KitchenProgress k, ProductionTicket t
            where t.id = k.ticketId and k.storeId = :storeId and k.status in :statuses and k.queuedAt < :before""")
    long countRoundsInStore(@Param("storeId") UUID storeId, @Param("statuses") Collection<KitchenStatus> statuses,
                            @Param("before") Instant before);

    Page<KitchenProgress> findByStoreIdAndDestinationAndStatusIn(UUID storeId, String destination,
                                                                 Collection<KitchenStatus> statuses,
                                                                 Pageable pageable);
}
