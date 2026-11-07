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

    List<OrderLinePriceAdjustment> findByLineIdInAndKind(Collection<UUID> lineIds, AdjustmentKind kind);

    /**
     * D112a: for each of {@code lineIds}, the line that holds its loyalty reward: the line itself, or the nearest
     * ancestor through transfer and split facts whose REWARD was recorded before the fact that moved the quantity
     * away from it (a split or transfer made before a reward never carries it). Lines without one are absent.
     */
    @Query(value = LineAncestry.ORIGIN + """
            SELECT DISTINCT ON (o.start_id) o.start_id AS lineId, o.line_id AS rewardedLineId
            FROM origin o
            JOIN orders.order_line_price_adjustment a ON a.line_id = o.line_id AND a.kind = 'REWARD'
                AND (o.bound IS NULL OR a.at <= o.bound) -- an equal instant counts as reward first (µs clock)
            ORDER BY o.start_id, o.depth""", nativeQuery = true)
    List<RewardOrigin> findRewardOrigins(@Param("lineIds") Collection<UUID> lineIds);

    /**
     * D112a: the net quantity the reward on {@code rewardedLineId} still rides on: that line and every line moved off
     * it, by a transfer or split recorded at or after the reward, and off those in turn; each line's quantity minus
     * its voids, transfers out and splits out.
     */
    @Query(value = """
            WITH RECURSIVE lineage (line_id) AS (
                SELECT CAST(:rewardedLineId AS UUID)
                UNION
                SELECT e.target_line_id
                FROM lineage g
                JOIN (SELECT tl.source_line_id, tl.target_line_id, t.transferred_at AS at
                      FROM orders.order_transfer_line tl JOIN orders.order_transfer t ON t.id = tl.transfer_id
                      UNION ALL
                      SELECT s.source_line_id, s.target_line_id, s.at FROM orders.order_line_split s) e
                    ON e.source_line_id = g.line_id
                WHERE e.at >= (SELECT min(a.at) FROM orders.order_line_price_adjustment a
                               WHERE a.line_id = :rewardedLineId AND a.kind = 'REWARD'))
            SELECT COALESCE(sum(l.quantity
                - COALESCE((SELECT sum(v.quantity) FROM orders.order_line_void v WHERE v.line_id = l.id), 0)
                - COALESCE((SELECT sum(t.quantity) FROM orders.order_transfer_line t WHERE t.source_line_id = l.id), 0)
                - COALESCE((SELECT sum(s.quantity) FROM orders.order_line_split s WHERE s.source_line_id = l.id), 0)),
                0)
            FROM lineage g JOIN orders.order_line l ON l.id = g.line_id""", nativeQuery = true)
    java.math.BigDecimal rewardLineageNet(@Param("rewardedLineId") UUID rewardedLineId);

    /**
     * S53: for each of {@code lineIds}, the adjustment its price origin comes from: the latest adjustment that is not
     * a reversal, on the line itself or, failing that, on the nearest ancestor before the quantity moved away from it.
     * On the line itself only those recorded at or before {@code asOf} count ({@code null}: all). Lines whose lineage
     * has none are absent.
     */
    @Query(value = LineAncestry.ORIGIN + """
            SELECT DISTINCT ON (o.start_id) o.start_id AS lineId, a.id AS adjustmentId
            FROM origin o
            JOIN orders.order_line_price_adjustment a ON a.line_id = o.line_id AND a.kind <> 'REVERSAL'
                AND (o.bound IS NULL OR a.at <= o.bound)
                AND (o.depth > 0 OR CAST(:asOf AS TIMESTAMP WITH TIME ZONE) IS NULL
                     OR a.at <= CAST(:asOf AS TIMESTAMP WITH TIME ZONE))
            ORDER BY o.start_id, o.depth, a.seq DESC""", nativeQuery = true)
    List<PriceOrigin> findPriceOrigins(@Param("lineIds") Collection<UUID> lineIds, @Param("asOf") java.time.Instant asOf);

    interface PriceOrigin {
        UUID getLineId();

        UUID getAdjustmentId();
    }

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
