package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditChargeRepository extends JpaRepository<CreditCharge, UUID> {

    List<CreditCharge> findByOrderIdOrderByCreatedAt(UUID orderId);

    List<CreditCharge> findByStoreIdAndStatusOrderByCreatedAt(UUID storeId, CreditChargeStatus status);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<CreditChargeStatus> statuses);

    /** The store and order of a charge, read alone before authorizing a command on it (D42). */
    @Query("""
            select new com.xavier.backend.settlement.repository.PartitionRef(c.storeId, c.orderId)
            from CreditCharge c where c.id = :id""")
    Optional<PartitionRef> findRefById(@Param("id") UUID id);

    /**
     * The sweeper's candidates (D74 recovery): RESERVED charges created before {@code before} that have their
     * document, oldest first.
     */
    @Query("""
            select c.id from CreditCharge c
            where c.status = com.xavier.backend.settlement.model.CreditChargeStatus.RESERVED
                and c.documentId is not null and c.createdAt < :before order by c.createdAt""")
    List<UUID> findReservedWithDocument(@Param("before") java.time.Instant before);

    /** Reporting's backfill (D84): a store's POSTED charges of business days {@code [from, to]}, oldest first. */
    @Query("""
            select c.id from CreditCharge c
            where c.storeId = :storeId and c.status = com.xavier.backend.settlement.model.CreditChargeStatus.POSTED
                and c.businessDate between :from and :to order by c.id""")
    Page<UUID> postedIds(@Param("storeId") UUID storeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                         Pageable page);

    /** Σ of the live (RESERVED and POSTED) charges per order, for the orders that have one. */
    @Query("""
            select new com.xavier.backend.settlement.repository.PaidTotal(c.orderId, sum(c.amount))
            from CreditCharge c where c.orderId in :orderIds
                and c.status <> com.xavier.backend.settlement.model.CreditChargeStatus.RELEASED
            group by c.orderId""")
    List<PaidTotal> creditFor(@Param("orderIds") Collection<UUID> orderIds);
}
