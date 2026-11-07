package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockLevelRepository extends JpaRepository<StockLevel, StockLevel.Key> {

    List<StockLevel> findByWarehouseIdOrderByStockItemId(UUID warehouseId);
}
