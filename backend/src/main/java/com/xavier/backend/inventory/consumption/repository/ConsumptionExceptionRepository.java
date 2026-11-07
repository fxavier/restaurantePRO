package com.xavier.backend.inventory.consumption.repository;

import com.xavier.backend.inventory.consumption.model.ConsumptionException;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ConsumptionExceptionRepository extends JpaRepository<ConsumptionException, UUID> {

    Page<ConsumptionException> findByStoreIdOrderByCreatedAtAscIdAsc(UUID storeId, Pageable pageable);

    Page<ConsumptionException> findByStoreIdAndResolvedAtIsNullOrderByCreatedAtAscIdAsc(UUID storeId,
                                                                                        Pageable pageable);

    @Query("SELECT e.storeId FROM ConsumptionException e WHERE e.id = :id")
    Optional<UUID> storeOf(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ConsumptionException e WHERE e.id = :id")
    Optional<ConsumptionException> lockById(UUID id);
}
