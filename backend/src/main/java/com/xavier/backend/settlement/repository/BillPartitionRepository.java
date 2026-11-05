package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.BillPartition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillPartitionRepository extends JpaRepository<BillPartition, UUID> {

    /** Every partition of the order, LIVE and CANCELLED, oldest first (ids are UUIDv7). */
    List<BillPartition> findByOrderIdOrderById(UUID orderId);

    boolean existsByOrderIdAndStatus(UUID orderId, com.xavier.backend.settlement.model.PartitionStatus status);

    /** The store and order of a partition, read alone before authorizing a command on it (D42). */
    @Query("""
            select new com.xavier.backend.settlement.repository.PartitionRef(p.storeId, p.orderId)
            from BillPartition p where p.id = :id""")
    Optional<PartitionRef> findRefById(@Param("id") UUID id);
}
