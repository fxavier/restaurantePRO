package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderLineVoid;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineVoidRepository extends JpaRepository<OrderLineVoid, UUID> {
}
