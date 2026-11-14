package com.xavier.backend.settlement.reconciliation.repository;

import com.xavier.backend.settlement.reconciliation.model.ReconciliationBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatch, UUID> {

    Optional<ReconciliationBatch> findByMethodCodeAndFileSha256(String methodCode, String fileSha256);

    Page<ReconciliationBatch> findByStoreIdOrderByImportedAtDescIdDesc(UUID storeId, Pageable page);

    /**
     * Serializes the imports of one payment method until the transaction ends (a transaction-level advisory lock), so
     * a second statement sees what the first matched (its DUPLICATE_LINEs) instead of racing it.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('settlement.reconciliation:' || :methodCode, 0))",
            nativeQuery = true)
    Integer lockImportsOf(@Param("methodCode") String methodCode);
}
