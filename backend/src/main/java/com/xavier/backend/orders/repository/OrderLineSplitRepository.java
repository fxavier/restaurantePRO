package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderLineSplit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineSplitRepository extends JpaRepository<OrderLineSplit, UUID> {
}
