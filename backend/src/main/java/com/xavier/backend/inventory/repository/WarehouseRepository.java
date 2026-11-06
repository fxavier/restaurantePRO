package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.Warehouse;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
}
