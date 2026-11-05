package com.xavier.backend.fiscal.repository;

import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.SourceType;
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

    /**
     * The document of a source. The status predicate repeats the one of the partial unique index
     * {@code fiscal_document_source_uk} as literals, so PostgreSQL can use that index (a bound parameter could not
     * prove it in a generic plan).
     */
    @Query("""
            select d from FiscalDocument d
            where d.sourceType = :sourceType and d.sourceId = :sourceId and d.documentType = :documentType
              and d.status in (com.xavier.backend.fiscal.model.DocumentStatus.DRAFT,
                               com.xavier.backend.fiscal.model.DocumentStatus.ISSUED)""")
    Optional<FiscalDocument> findBySource(@Param("sourceType") SourceType sourceType, @Param("sourceId") UUID sourceId,
                                          @Param("documentType") DocumentType documentType);

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
