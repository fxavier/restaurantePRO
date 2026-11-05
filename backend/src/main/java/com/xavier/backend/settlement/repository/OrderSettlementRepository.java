package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.OrderSettlement;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderSettlementRepository extends JpaRepository<OrderSettlement, UUID> {

    /** Creates the order's lock row once; a concurrent or later call changes nothing. */
    @Modifying
    @Query(value = """
            INSERT INTO settlement.order_settlement (order_id, created_at) VALUES (:orderId, :now)
            ON CONFLICT (order_id) DO NOTHING""", nativeQuery = true)
    void createIfAbsent(@Param("orderId") UUID orderId, @Param("now") Instant now);

    /** {@code SELECT ... FOR UPDATE} on the order's lock row, until the caller's transaction ends (D51). */
    @Query(value = "SELECT order_id FROM settlement.order_settlement WHERE order_id = :orderId FOR UPDATE",
            nativeQuery = true)
    UUID lock(@Param("orderId") UUID orderId);
}
