package com.xavier.backend.inventory.purchasing.repository;

import com.xavier.backend.inventory.purchasing.model.CostVariance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CostVarianceRepository extends JpaRepository<CostVariance, UUID> {
}
