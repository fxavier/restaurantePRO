package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.Payment;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByOrderId(UUID orderId);

    boolean existsByAttemptId(UUID attemptId);

    /** Σ of the order's payments (the "paid" of §8.3), zero when there is none. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.orderId = :orderId")
    BigDecimal paidFor(@Param("orderId") UUID orderId);

    /** Σ paid per order, for the orders that have payments. */
    @Query("""
            select new com.xavier.backend.settlement.repository.PaidTotal(p.orderId, sum(p.amount))
            from Payment p where p.orderId in :orderIds group by p.orderId""")
    List<PaidTotal> paidFor(@Param("orderIds") Collection<UUID> orderIds);
}
