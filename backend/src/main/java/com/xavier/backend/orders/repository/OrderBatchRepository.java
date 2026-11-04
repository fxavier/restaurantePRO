package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderBatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderBatchRepository extends JpaRepository<OrderBatch, UUID> {

    List<OrderBatch> findByOrderIdOrderByBatchNo(UUID orderId);

    /**
     * CONFIRMED → DISPATCHED as one compare-and-set: of two concurrent notices exactly one changes the row.
     *
     * @return 1 when this call made the transition, 0 when the batch was already DISPATCHED (or does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrderBatch b set b.status = com.xavier.backend.orders.model.BatchStatus.DISPATCHED,
                b.dispatchedAt = :at
            where b.id = :id and b.status = com.xavier.backend.orders.model.BatchStatus.CONFIRMED""")
    int markDispatched(@Param("id") UUID id, @Param("at") Instant at);
}
