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
