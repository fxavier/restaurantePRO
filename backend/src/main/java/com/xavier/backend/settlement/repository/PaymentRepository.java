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

    /** Only the payment's store, read alone before authorizing a command on it (D42). */
    @Query("select p.storeId from Payment p where p.id = :id")
    java.util.Optional<UUID> findStoreIdById(@Param("id") UUID id);

    boolean existsByAttemptId(UUID attemptId);

    boolean existsByPartitionId(UUID partitionId);

    /** Σ of the order's payments per target (D69): the ORDER target and each partition that was paid. */
    @Query("""
            select new com.xavier.backend.settlement.repository.TargetPaid(p.targetType, p.partitionId, sum(p.amount))
            from Payment p where p.orderId = :orderId group by p.targetType, p.partitionId""")
    List<TargetPaid> paidByTarget(@Param("orderId") UUID orderId);

    /**
     * The ids of a store's payments whose business day is in {@code [from, to]}, oldest first (reporting): by their
     * own business date, or, for a payment recorded before R3 (none stored), by its session being one of
     * {@code sessions} (the store's sessions of those days).
     */
    @Query("""
            select p.id from Payment p where p.storeId = :storeId
                and (p.businessDate between :from and :to or (p.businessDate is null and p.sessionId in :sessions))
            order by p.id""")
    org.springframework.data.domain.Page<UUID> idsOf(@Param("storeId") UUID storeId,
                                                     @Param("from") java.time.LocalDate from,
                                                     @Param("to") java.time.LocalDate to,
                                                     @Param("sessions") Collection<UUID> sessions,
                                                     org.springframework.data.domain.Pageable page);

    /** Σ of the order's payments (the "paid" of §8.3), zero when there is none. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.orderId = :orderId")
    BigDecimal paidFor(@Param("orderId") UUID orderId);

    /** Σ paid per order, for the orders that have payments. */
    @Query("""
            select new com.xavier.backend.settlement.repository.PaidTotal(p.orderId, sum(p.amount))
            from Payment p where p.orderId in :orderIds group by p.orderId""")
    List<PaidTotal> paidFor(@Param("orderIds") Collection<UUID> orderIds);
}
