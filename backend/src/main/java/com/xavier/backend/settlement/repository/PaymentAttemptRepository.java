package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByOrderIdAndStatusInOrderByCreatedAt(UUID orderId, Collection<AttemptStatus> statuses);

    Optional<PaymentAttempt> findFirstBySessionIdAndStatusIn(UUID sessionId, Collection<AttemptStatus> statuses);

    Optional<PaymentAttempt> findFirstByStoreIdAndStatusIn(UUID storeId, Collection<AttemptStatus> statuses);

    List<PaymentAttempt> findByStoreIdAndStatusInOrderByCreatedAt(UUID storeId, Collection<AttemptStatus> statuses);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<AttemptStatus> statuses);

    Optional<PaymentAttempt> findFirstByCreditAccountIdAndStatusIn(UUID creditAccountId,
                                                                  Collection<AttemptStatus> statuses);

    boolean existsByPartitionIdAndStatusIn(UUID partitionId, Collection<AttemptStatus> statuses);

    long countByStatus(AttemptStatus status);

    /** The store and order of an attempt, read alone before authorizing a command on it (D42). */
    @Query("""
            select new com.xavier.backend.settlement.repository.AttemptRef(a.storeId, a.orderId, a.creditAccountId)
            from PaymentAttempt a where a.id = :id""")
    Optional<AttemptRef> findRefById(@Param("id") UUID id);

    /**
     * D48 tx-a, the compare-and-set: CREATED → PENDING. Of two submitters exactly one changes the row.
     * @return 1 when this call moved the attempt, 0 when it was not CREATED any more
     */
    @Modifying
    @Query("""
            update PaymentAttempt a set a.status = com.xavier.backend.settlement.model.AttemptStatus.PENDING,
                a.updatedAt = :now, a.version = a.version + 1
            where a.id = :id and a.status = com.xavier.backend.settlement.model.AttemptStatus.CREATED""")
    int markSubmitted(@Param("id") UUID id, @Param("now") Instant now);

    /** D81 (2): the UNKNOWN attempts due a provider status query at {@code now}, the most overdue first. */
    @Query("""
            select a.id from PaymentAttempt a
            where a.status = com.xavier.backend.settlement.model.AttemptStatus.UNKNOWN
                and (a.nextPollAt is null or a.nextPollAt <= :now)
            order by a.nextPollAt nulls first""")
    List<UUID> findDueForPoll(@Param("now") Instant now);

    /** D48 sweeper: attempts of {@code kind} in {@code status} not touched since {@code before}, oldest first. */
    @Query("""
            select a.id from PaymentAttempt a
            where a.status = :status and a.methodKind = :kind and a.updatedAt < :before order by a.updatedAt""")
    List<UUID> findStale(@Param("status") AttemptStatus status, @Param("kind") PaymentMethodKind kind,
                         @Param("before") Instant before);
}
