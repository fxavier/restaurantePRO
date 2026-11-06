package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockItem;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    Page<StockItem> findAllByOrderByCodeAsc(Pageable pageable);

    long countByIdIn(Collection<UUID> ids);
}
