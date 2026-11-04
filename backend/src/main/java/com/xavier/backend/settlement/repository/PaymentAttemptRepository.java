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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByOrderIdAndStatusInOrderByCreatedAt(UUID orderId, Collection<AttemptStatus> statuses);

    Optional<PaymentAttempt> findFirstBySessionIdAndStatusIn(UUID sessionId, Collection<AttemptStatus> statuses);

    Optional<PaymentAttempt> findFirstByStoreIdAndStatusIn(UUID storeId, Collection<AttemptStatus> statuses);

    List<PaymentAttempt> findByStoreIdAndStatusInOrderByCreatedAt(UUID storeId, Collection<AttemptStatus> statuses);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<AttemptStatus> statuses);

    long countByStatus(AttemptStatus status);

    /** The store and order of an attempt, read alone before authorizing a command on it (D42). */
    @Query("""
            select new com.xavier.backend.settlement.repository.AttemptRef(a.storeId, a.orderId)
            from PaymentAttempt a where a.id = :id""")
    Optional<AttemptRef> findRefById(@Param("id") UUID id);

    /** D48 sweeper: attempts of {@code kind} in {@code status} not touched since {@code before}, oldest first. */
    @Query("""
            select a.id from PaymentAttempt a
            where a.status = :status and a.methodKind = :kind and a.updatedAt < :before order by a.updatedAt""")
    List<UUID> findStale(@Param("status") AttemptStatus status, @Param("kind") PaymentMethodKind kind,
                         @Param("before") Instant before);
}
