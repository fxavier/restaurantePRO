package com.xavier.backend.promotions.loyalty.repository;

import com.xavier.backend.promotions.loyalty.model.EntryKind;
import com.xavier.backend.promotions.loyalty.model.LoyaltyPointEntry;
import com.xavier.backend.promotions.loyalty.model.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoyaltyPointEntryRepository extends JpaRepository<LoyaltyPointEntry, UUID> {

    /** The balance: Σ points, served by {@code loyalty_point_entry_customer_ix} (D110, LoyaltyBalancePlanTest). */
    String BALANCE = """
            SELECT coalesce(sum(points), 0) FROM promotions.loyalty_point_entry WHERE customer_id = :customerId""";

    @Query(nativeQuery = true, value = BALANCE)
    long balance(@Param("customerId") UUID customerId);

    /** The balance before {@code :from}: the opening balance of a statement. */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(points), 0) FROM promotions.loyalty_point_entry
            WHERE customer_id = :customerId AND occurred_at < :from""")
    long balanceBefore(@Param("customerId") UUID customerId, @Param("from") Instant from);

    List<LoyaltyPointEntry> findByCustomerIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(UUID customerId,
                                                                                          Instant from, Instant to);

    Optional<LoyaltyPointEntry> findBySourceTypeAndSourceIdAndKind(SourceType sourceType, UUID sourceId,
                                                                   EntryKind kind);

    /** The member's lots up to {@code :until} that still have points, oldest first (FIFO, D110). */
    @Query(nativeQuery = true, value = """
            SELECT e.id AS entryId, e.points - coalesce(sum(a.points), 0) AS remaining
            FROM promotions.loyalty_point_entry e
            LEFT JOIN promotions.loyalty_allocation a ON a.earn_entry_id = e.id
            WHERE e.customer_id = :customerId AND e.points > 0 AND e.occurred_at <= :until
            GROUP BY e.id, e.points, e.occurred_at
            HAVING e.points - coalesce(sum(a.points), 0) > 0
            ORDER BY e.occurred_at, e.id""")
    List<PointLot> openLots(@Param("customerId") UUID customerId, @Param("until") Instant until);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO promotions.loyalty_allocation (debit_entry_id, earn_entry_id, points)
            VALUES (:debitId, :lotId, :points)""")
    void allocate(@Param("debitId") UUID debitId, @Param("lotId") UUID lotId, @Param("points") int points);
}
