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

    /** What the facts take off each confirmed line of the orders: Σ voids + Σ transferred out (never stored). */
    @Query(value = """
            SELECT l.id AS lineId,
                   COALESCE((SELECT sum(v.quantity) FROM orders.order_line_void v WHERE v.line_id = l.id), 0)
                 + COALESCE((SELECT sum(t.quantity) FROM orders.order_transfer_line t
                             WHERE t.source_line_id = l.id), 0) AS deducted
            FROM orders.order_line l
            WHERE l.order_id IN (:orderIds) AND l.status = 'CONFIRMED'""", nativeQuery = true)
    List<LineDeduction> deductions(@Param("orderIds") Collection<UUID> orderIds);

    interface LineDeduction {
        UUID getLineId();

        BigDecimal getDeducted();
    }
}
