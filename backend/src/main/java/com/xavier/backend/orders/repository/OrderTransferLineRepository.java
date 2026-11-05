package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderTransferLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderTransferLineRepository extends JpaRepository<OrderTransferLine, UUID> {
}
