package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLinePriceAdjustmentRepository extends JpaRepository<OrderLinePriceAdjustment, UUID> {

    /** Every adjustment of the orders, per line in {@code seq} order: the last one is the line's effective price. */
    List<OrderLinePriceAdjustment> findByOrderIdInOrderByLineIdAscSeqAsc(Collection<UUID> orderIds);

    List<OrderLinePriceAdjustment> findByLineIdOrderBySeq(UUID lineId);

    boolean existsByOrderIdAndKind(UUID orderId, AdjustmentKind kind);

    boolean existsByLineIdAndKind(UUID lineId, AdjustmentKind kind);

    /**
     * D112a: for each of {@code lineIds}, the line that holds its loyalty reward: the line itself, or the nearest
     * ancestor through transfer and split facts whose REWARD was recorded before the fact that moved the quantity
     * away from it (a split or transfer made before a reward never carries it). Lines without one are absent.
     */
    @Query(value = """
            WITH RECURSIVE origin (start_id, line_id, bound, depth) AS (
                SELECT l.id, l.id, CAST(NULL AS TIMESTAMP WITH TIME ZONE), 0
                FROM orders.order_line l WHERE l.id IN (:lineIds)
                UNION ALL
                SELECT o.start_id, e.source_line_id, e.at, o.depth + 1
                FROM origin o
                JOIN (SELECT tl.target_line_id, tl.source_line_id, t.transferred_at AS at
                      FROM orders.order_transfer_line tl JOIN orders.order_transfer t ON t.id = tl.transfer_id
                      UNION ALL
                      SELECT s.target_line_id, s.source_line_id, s.at FROM orders.order_line_split s) e
                    ON e.target_line_id = o.line_id)
            SELECT DISTINCT ON (o.start_id) o.start_id AS lineId, o.line_id AS rewardedLineId
            FROM origin o
            JOIN orders.order_line_price_adjustment a ON a.line_id = o.line_id AND a.kind = 'REWARD'
                AND (o.bound IS NULL OR a.at <= o.bound)
            ORDER BY o.start_id, o.depth""", nativeQuery = true)
    List<RewardOrigin> findRewardOrigins(@Param("lineIds") Collection<UUID> lineIds);

    interface RewardOrigin {
        UUID getLineId();

        UUID getRewardedLineId();
    }

    /**
     * The store's discount sources whose order's business day is in {@code [from, to]}: rounds with a promoted line
     * and price adjustments, in id (creation) order across both.
     */
    @Query(value = """
            SELECT s.source_type AS sourceType, s.source_id AS sourceId FROM (
                SELECT 'BATCH' AS source_type, b.id AS source_id, b.order_id
                FROM orders.order_batch b
                WHERE EXISTS (SELECT 1 FROM orders.order_line l WHERE l.batch_id = b.id AND l.promotion_id IS NOT NULL)
                UNION ALL
                SELECT 'ADJUSTMENT', a.id, a.order_id FROM orders.order_line_price_adjustment a) s
            JOIN orders.order_header o ON o.id = s.order_id
            WHERE o.store_id = :storeId AND o.business_day BETWEEN :from AND :to
            ORDER BY s.source_id""",
            countQuery = """
            SELECT count(*) FROM (
                SELECT b.order_id FROM orders.order_batch b
                WHERE EXISTS (SELECT 1 FROM orders.order_line l WHERE l.batch_id = b.id AND l.promotion_id IS NOT NULL)
                UNION ALL
                SELECT a.order_id FROM orders.order_line_price_adjustment a) s
            JOIN orders.order_header o ON o.id = s.order_id
            WHERE o.store_id = :storeId AND o.business_day BETWEEN :from AND :to""",
            nativeQuery = true)
    Page<DiscountSource> findDiscountSources(@Param("storeId") UUID storeId, @Param("from") LocalDate from,
                                             @Param("to") LocalDate to, Pageable pageable);

    interface DiscountSource {
        String getSourceType();

        UUID getSourceId();
    }
}
