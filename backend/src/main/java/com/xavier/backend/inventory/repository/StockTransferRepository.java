package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockTransfer;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {

    @Query("""
            SELECT t FROM StockTransfer t WHERE t.fromStoreId = :storeId OR t.toStoreId = :storeId
            ORDER BY t.createdAt DESC, t.id DESC""")
    Page<StockTransfer> findOfStore(UUID storeId, Pageable pageable);
}
