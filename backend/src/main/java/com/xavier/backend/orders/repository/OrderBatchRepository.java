package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderBatchRepository extends JpaRepository<OrderBatch, UUID> {

    List<OrderBatch> findByOrderIdOrderByBatchNo(UUID orderId);
}
