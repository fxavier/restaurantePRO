package com.xavier.backend.fiscal.repository;

import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.SourceType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalDocumentRepository extends JpaRepository<FiscalDocument, UUID> {

    Optional<FiscalDocument> findBySourceTypeAndSourceIdAndDocumentType(SourceType sourceType, UUID sourceId,
                                                                       DocumentType documentType);

    Optional<FiscalDocument> findBySeriesIdAndNumber(UUID seriesId, Long number);

    Page<FiscalDocument> findByStoreIdAndStatusAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
            UUID storeId, DocumentStatus status, Instant from, Instant to, Pageable pageable);

    /** Only the store, for the D42 check order: 404 if absent, then authorize, then load. */
    @Query("select d.storeId from FiscalDocument d where d.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    long countByStoreIdAndStatus(UUID storeId, DocumentStatus status);

    /** The document lock (D50 §7.6, D51): taken before the series lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FiscalDocument d where d.id = :id")
    Optional<FiscalDocument> lockById(@Param("id") UUID id);
}
