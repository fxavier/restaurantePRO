package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.PurchaseOrder;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Page<PurchaseOrder> findByStoreIdOrderByCreatedAtDesc(UUID storeId, Pageable pageable);
}
