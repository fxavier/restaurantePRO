package com.xavier.backend.fiscal.repository;

import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalSeries;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalSeriesRepository extends JpaRepository<FiscalSeries, UUID> {

    Page<FiscalSeries> findByStoreId(UUID storeId, Pageable pageable);

    boolean existsByStoreIdAndDocumentTypeAndCode(UUID storeId, DocumentType documentType, String code);

    boolean existsByStoreIdAndDocumentTypeAndActiveTrue(UUID storeId, DocumentType documentType);

    /** The numbering lock (D50 §7.6, D51): held until the issuing transaction ends, so issues serialize per series. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FiscalSeries s where s.storeId = :storeId and s.documentType = :type and s.active = true")
    Optional<FiscalSeries> lockActive(@Param("storeId") UUID storeId, @Param("type") DocumentType type);
}
