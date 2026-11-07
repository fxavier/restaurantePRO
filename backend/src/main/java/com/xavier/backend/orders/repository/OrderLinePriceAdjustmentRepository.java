package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLinePriceAdjustmentRepository extends JpaRepository<OrderLinePriceAdjustment, UUID> {

    /** Every adjustment of the orders, per line in {@code seq} order: the last one is the line's effective price. */
    List<OrderLinePriceAdjustment> findByOrderIdInOrderByLineIdAscSeqAsc(Collection<UUID> orderIds);

    List<OrderLinePriceAdjustment> findByLineIdOrderBySeq(UUID lineId);

    boolean existsByOrderIdAndKind(UUID orderId, AdjustmentKind kind);

    boolean existsByLineIdAndKind(UUID lineId, AdjustmentKind kind);
}
