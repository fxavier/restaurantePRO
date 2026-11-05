package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.Refund;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByOrderIdOrderById(UUID orderId);

    /** Σ refunds of a payment, zero when there is none. */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId")
    BigDecimal refundedOf(@Param("paymentId") UUID paymentId);

    /** Σ of the order's refunds per target, as {@link PaymentRepository#paidByTarget} sums its payments. */
    @Query("""
            select new com.xavier.backend.settlement.repository.TargetPaid(r.targetType, r.partitionId, sum(r.amount))
            from Refund r where r.orderId = :orderId group by r.targetType, r.partitionId""")
    List<TargetPaid> refundedByTarget(@Param("orderId") UUID orderId);

    /** Σ refunded per order, for the orders that have refunds. */
    @Query("""
            select new com.xavier.backend.settlement.repository.PaidTotal(r.orderId, sum(r.amount))
            from Refund r where r.orderId in :orderIds group by r.orderId""")
    List<PaidTotal> refundedFor(@Param("orderIds") Collection<UUID> orderIds);

    /** The ids of a store's refunds of business days {@code [from, to]}, oldest first (reporting). */
    @Query("""
            select r.id from Refund r where r.storeId = :storeId and r.businessDate between :from and :to
            order by r.id""")
    Page<UUID> idsOf(@Param("storeId") UUID storeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                     Pageable page);
}
