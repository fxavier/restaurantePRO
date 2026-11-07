package com.xavier.backend.inventory.count.repository;

import com.xavier.backend.inventory.count.model.CountKind;
import com.xavier.backend.inventory.count.model.CountStatus;
import com.xavier.backend.inventory.count.model.StockCount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StockCountRepository extends JpaRepository<StockCount, UUID> {

    /** The store of a count alone, to authorize before anything else is read (D42 (b)). */
    @Query("SELECT c.storeId FROM StockCount c WHERE c.id = :id")
    Optional<UUID> storeOf(UUID id);

    /** The count row FOR UPDATE (its lines load lazily, in the same transaction). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StockCount c WHERE c.id = :id")
    Optional<StockCount> findForUpdate(UUID id);

    boolean existsByWarehouseIdAndKindAndStatus(UUID warehouseId, CountKind kind, CountStatus status);
}
