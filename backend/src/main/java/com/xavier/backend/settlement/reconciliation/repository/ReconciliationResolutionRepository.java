package com.xavier.backend.settlement.reconciliation.repository;

import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationResolutionRepository extends JpaRepository<ReconciliationResolution, UUID> {

    List<ReconciliationResolution> findByEntryIdIn(Collection<UUID> entryIds);

    boolean existsByEntryId(UUID entryId);
}
