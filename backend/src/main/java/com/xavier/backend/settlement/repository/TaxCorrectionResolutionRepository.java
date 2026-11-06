package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.TaxCorrectionResolution;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxCorrectionResolutionRepository extends JpaRepository<TaxCorrectionResolution, UUID> {

    List<TaxCorrectionResolution> findByAdjustmentIdIn(Collection<UUID> adjustmentIds);

    boolean existsByAdjustmentId(UUID adjustmentId);
}
