package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.Warehouse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByStoreIdOrderByCode(UUID storeId);

    /** The store of a warehouse alone, to authorize before anything else is read (D42 (b)). */
    @Query("SELECT w.storeId FROM Warehouse w WHERE w.id = :id")
    Optional<UUID> storeOf(UUID id);
}
