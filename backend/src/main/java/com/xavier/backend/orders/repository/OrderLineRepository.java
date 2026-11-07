package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.LineStatus;
import com.xavier.backend.orders.model.OrderLine;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    /** Ids are UUIDv7, so id order is creation order. */
    List<OrderLine> findByOrderIdOrderById(UUID orderId);

    List<OrderLine> findByOrderIdAndStatusOrderById(UUID orderId, LineStatus status);

    List<OrderLine> findByOrderIdInAndStatusOrderById(Collection<UUID> orderIds, LineStatus status);

    List<OrderLine> findByOrderIdAndIdIn(UUID orderId, Collection<UUID> ids);

    Optional<OrderLine> findByIdAndOrderId(UUID id, UUID orderId);

    /**
     * The round line {@code lineId} descends from (D100): the line itself when it has a round, else the source line
     * its transfer and split facts lead back to. A confirmed line always has one or the other.
     */
    @Query(value = """
            WITH RECURSIVE origin (line_id, batch_id) AS (
                SELECT l.id, l.batch_id FROM orders.order_line l WHERE l.id = :lineId
                UNION ALL
                SELECT s.id, s.batch_id
                FROM origin o
                JOIN (SELECT target_line_id, source_line_id FROM orders.order_transfer_line
                      UNION ALL
                      SELECT target_line_id, source_line_id FROM orders.order_line_split) t
                    ON t.target_line_id = o.line_id
                JOIN orders.order_line s ON s.id = t.source_line_id
                WHERE o.batch_id IS NULL)
            SELECT line_id FROM origin WHERE batch_id IS NOT NULL""", nativeQuery = true)
    Optional<UUID> roundLineOf(@Param("lineId") UUID lineId);

    /** The lines confirmed in a round, in creation order; a line transferred in has no round. */
    List<OrderLine> findByBatchIdOrderById(UUID batchId);

    /**
     * What the facts take off each confirmed line of the orders: Σ voids + Σ transferred out + Σ split out (D105;
     * never stored).
     */
    @Query(value = """
            SELECT l.id AS lineId,
                   COALESCE((SELECT sum(v.quantity) FROM orders.order_line_void v WHERE v.line_id = l.id), 0)
                 + COALESCE((SELECT sum(t.quantity) FROM orders.order_transfer_line t
                             WHERE t.source_line_id = l.id), 0)
                 + COALESCE((SELECT sum(s.quantity) FROM orders.order_line_split s
                             WHERE s.source_line_id = l.id), 0) AS deducted
            FROM orders.order_line l
            WHERE l.order_id IN (:orderIds) AND l.status = 'CONFIRMED'""", nativeQuery = true)
    List<LineDeduction> deductions(@Param("orderIds") Collection<UUID> orderIds);

    /**
     * Locks a confirmed line's row without changing it (the row trigger fires on UPDATE only), so the voids of one
     * reward's lineage queue (D112a). {@code NO KEY UPDATE} leaves the foreign-key share locks of new voids alone.
     */
    @Query(value = "SELECT id FROM orders.order_line WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    UUID lockForRewardLineage(@Param("id") UUID id);

    interface LineDeduction {
        UUID getLineId();

        BigDecimal getDeducted();
    }
}
