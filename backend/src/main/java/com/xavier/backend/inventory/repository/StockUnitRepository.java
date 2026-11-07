package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.StockUnit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockUnitRepository extends JpaRepository<StockUnit, String> {
}
