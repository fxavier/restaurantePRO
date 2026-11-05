package com.xavier.backend.fiscal.repository;

import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.SourceType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** Every document of an order (by {@code order_ref}, so R2 documents too), oldest first. */
    @Query("""
            select d from FiscalDocument d
            where d.orderRef = :orderId
              and d.status in (com.xavier.backend.fiscal.model.DocumentStatus.DRAFT,
                               com.xavier.backend.fiscal.model.DocumentStatus.ISSUED)
            order by d.createdAt, d.id""")
    List<FiscalDocument> findByOrder(@Param("orderId") UUID orderId);

    /**
     * ISSUED documents of a store whose business day is in {@code [from, to]}; an R2 document (no business date) is
     * in range when it was issued in {@code [start, end)}, the instants those business days span.
     */
    @Query(value = """
            select d.id from FiscalDocument d
            where d.storeId = :storeId and d.status = com.xavier.backend.fiscal.model.DocumentStatus.ISSUED
              and (d.businessDate between :from and :to
                   or (d.businessDate is null and d.issuedAt >= :start and d.issuedAt < :end))
            order by d.issuedAt, d.id""",
            countQuery = """
            select count(d) from FiscalDocument d
            where d.storeId = :storeId and d.status = com.xavier.backend.fiscal.model.DocumentStatus.ISSUED
              and (d.businessDate between :from and :to
                   or (d.businessDate is null and d.issuedAt >= :start and d.issuedAt < :end))""")
    Page<UUID> findIssuedIds(@Param("storeId") UUID storeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("start") Instant start, @Param("end") Instant end, Pageable page);

    Optional<FiscalDocument> findBySeriesIdAndNumber(UUID seriesId, Long number);

    /** ISSUED documents of a store in {@code [from, to)}; a {@code null} order or customer does not filter. */
    @Query("""
            select d from FiscalDocument d
            where d.storeId = :storeId and d.status = com.xavier.backend.fiscal.model.DocumentStatus.ISSUED
              and d.issuedAt >= :from and d.issuedAt < :to
              and (:orderId is null or d.orderRef = :orderId)
              and (:customerId is null or d.customerId = :customerId)""")
    Page<FiscalDocument> findIssued(@Param("storeId") UUID storeId, @Param("from") Instant from,
                                    @Param("to") Instant to, @Param("orderId") UUID orderId,
                                    @Param("customerId") UUID customerId, Pageable pageable);

    /** Only the store, for the D42 check order: 404 if absent, then authorize, then load. */
    @Query("select d.storeId from FiscalDocument d where d.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    long countByStoreIdAndStatus(UUID storeId, DocumentStatus status);

    /** The document lock (D50 §7.6, D51): taken before the series lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FiscalDocument d where d.id = :id")
    Optional<FiscalDocument> lockById(@Param("id") UUID id);
}
