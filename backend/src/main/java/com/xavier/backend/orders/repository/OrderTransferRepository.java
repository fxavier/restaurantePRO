package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderTransfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderTransferRepository extends JpaRepository<OrderTransfer, UUID> {
}
