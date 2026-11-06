package com.xavier.backend.settlement.reconciliation.repository;

import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationEntryRepository extends JpaRepository<ReconciliationEntry, UUID> {

    /** Only the entry's store, read alone before authorizing on it (D42). */
    @Query("select e.storeId from ReconciliationEntry e where e.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    /** Which of {@code paymentIds} an earlier batch already matched (MATCHED or AMOUNT_MISMATCH). */
    @Query("""
            select e.paymentId from ReconciliationEntry e where e.paymentId in :paymentIds
                and e.kind in (com.xavier.backend.settlement.reconciliation.model.EntryKind.MATCHED,
                               com.xavier.backend.settlement.reconciliation.model.EntryKind.AMOUNT_MISMATCH)""")
    List<UUID> matchedAmong(@Param("paymentIds") Collection<UUID> paymentIds);

    /**
     * A batch's entries in line order (MISSING_PROVIDER, which have no line, last), of {@code kind} unless null, open
     * (no resolution) or resolved unless {@code open} is null.
     */
    @Query("""
            select e from ReconciliationEntry e where e.batchId = :batchId
                and (:kind is null or e.kind = :kind)
                and (:open is null
                    or (:open = true and not exists (select 1 from ReconciliationResolution r where r.entryId = e.id))
                    or (:open = false and exists (select 1 from ReconciliationResolution r where r.entryId = e.id)))
            order by e.lineNo nulls last, e.occurredAt, e.id""")
    Page<ReconciliationEntry> page(@Param("batchId") UUID batchId, @Param("kind") EntryKind kind,
                                   @Param("open") Boolean open, Pageable page);

    @Query("""
            select new com.xavier.backend.settlement.reconciliation.repository.KindCount(e.batchId, e.kind, count(e),
                sum(case when exists (select 1 from ReconciliationResolution r where r.entryId = e.id) then 0 else 1 end))
            from ReconciliationEntry e where e.batchId in :batchIds group by e.batchId, e.kind""")
    List<KindCount> countsOf(@Param("batchIds") Collection<UUID> batchIds);
}
