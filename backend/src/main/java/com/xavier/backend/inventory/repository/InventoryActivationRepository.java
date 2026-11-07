package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.InventoryActivation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryActivationRepository extends JpaRepository<InventoryActivation, UUID> {
}
