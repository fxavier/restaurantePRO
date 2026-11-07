package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.PurchaseOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /** The store of an order alone, to authorize before anything else is read (D42). */
    @Query("SELECT o.storeId FROM PurchaseOrder o WHERE o.id = :id")
    Optional<UUID> storeOf(UUID id);

    Page<PurchaseOrder> findByStoreIdOrderByCreatedAtDesc(UUID storeId, Pageable pageable);
}
