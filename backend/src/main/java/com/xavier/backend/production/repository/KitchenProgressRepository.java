package com.xavier.backend.production.repository;

import com.xavier.backend.production.model.KitchenProgress;
import com.xavier.backend.production.model.KitchenStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KitchenProgressRepository extends JpaRepository<KitchenProgress, UUID> {

    Page<KitchenProgress> findByStoreIdAndStatusIn(UUID storeId, Collection<KitchenStatus> statuses,
                                                   Pageable pageable);

    Page<KitchenProgress> findByStoreIdAndDestinationAndStatusIn(UUID storeId, String destination,
                                                                 Collection<KitchenStatus> statuses,
                                                                 Pageable pageable);

    /** The progress of every station of every round of {@code orderId}, oldest first: one query per order. */
    @Query("""
            select t.sourceId as batchId, p.status as status
            from KitchenProgress p, ProductionTicket t
            where t.id = p.ticketId and t.orderId = :orderId
                and t.sourceType = com.xavier.backend.production.model.TicketSource.BATCH
            order by p.queuedAt""")
    List<StationProgress> stationsOfOrder(@Param("orderId") UUID orderId);

    interface StationProgress {
        UUID getBatchId();

        KitchenStatus getStatus();
    }
}
