package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.TaxRoundingAdjustment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaxRoundingAdjustmentRepository extends JpaRepository<TaxRoundingAdjustment, UUID> {

    List<TaxRoundingAdjustment> findByOrderIdOrderById(UUID orderId);

    /** A store's pending corrections, oldest first: {@code open} true → unresolved, false → resolved, null → all. */
    @Query("""
            select a from TaxRoundingAdjustment a where a.storeId = :storeId
            and a.kind = com.xavier.backend.settlement.model.TaxRoundingAdjustment.Kind.PENDING_CORRECTION
            and (:open is null
                 or (:open = true and not exists (select r from TaxCorrectionResolution r where r.adjustmentId = a.id))
                 or (:open = false and exists (select r from TaxCorrectionResolution r where r.adjustmentId = a.id)))
            order by a.createdAt, a.id""")
    Page<TaxRoundingAdjustment> pendingOf(@Param("storeId") UUID storeId, @Param("open") Boolean open,
                                          Pageable page);
}
