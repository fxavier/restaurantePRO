package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockItemPack;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockItemPackRepository extends JpaRepository<StockItemPack, UUID> {

    List<StockItemPack> findByStockItemIdOrderByCode(UUID stockItemId);
}
