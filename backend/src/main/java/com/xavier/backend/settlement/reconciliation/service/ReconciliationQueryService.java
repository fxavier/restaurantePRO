package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationBatch;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import com.xavier.backend.settlement.reconciliation.model.dto.BatchView;
import com.xavier.backend.settlement.reconciliation.model.dto.EntryView;
import com.xavier.backend.settlement.reconciliation.repository.KindCount;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationBatchRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationEntryRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationResolutionRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The reconciliation reads (PRD S31), with {@code RECONCILIATION_VIEW} in the store. */
@Service
public class ReconciliationQueryService {

    private final AccessPolicy accessPolicy;
    private final ReconciliationBatchRepository batches;
    private final ReconciliationEntryRepository entries;
    private final ReconciliationResolutionRepository resolutions;

    ReconciliationQueryService(AccessPolicy accessPolicy, ReconciliationBatchRepository batches,
                               ReconciliationEntryRepository entries,
                               ReconciliationResolutionRepository resolutions) {
        this.accessPolicy = accessPolicy;
        this.batches = batches;
        this.entries = entries;
        this.resolutions = resolutions;
    }

    /** The store's batches, the latest import first, with their entry counts. */
    @Transactional(readOnly = true)
    public PageResponse<BatchView> batches(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.RECONCILIATION_VIEW, storeId);
        Page<ReconciliationBatch> page = batches.findByStoreIdOrderByImportedAtDescIdDesc(storeId, pageable);
        Map<UUID, List<KindCount>> counts = page.isEmpty() ? Map.of()
                : entries.countsOf(page.map(ReconciliationBatch::getId).getContent()).stream()
                .collect(Collectors.groupingBy(KindCount::batchId));
        return PageResponse.from(page.map(batch -> ReconciliationViews.batch(batch,
                counts.getOrDefault(batch.getId(), List.of()))));
    }

    /** A batch's entries in line order; {@code kind} and {@code open} filter when given. */
    @Transactional(readOnly = true)
    public PageResponse<EntryView> entries(UUID batchId, EntryKind kind, Boolean open, Pageable pageable) {
        UUID storeId = batches.findStoreIdById(batchId)
                .orElseThrow(() -> new ReconciliationNotFoundException("ReconciliationBatch", batchId));
        accessPolicy.require(Permission.RECONCILIATION_VIEW, storeId);
        ReconciliationBatch batch = batches.findById(batchId).orElseThrow();
        Page<ReconciliationEntry> page = entries.page(batchId, kind, open, pageable);
        Map<UUID, ReconciliationResolution> decided = page.isEmpty() ? Map.of()
                : resolutions.findByEntryIdIn(page.map(ReconciliationEntry::getId).getContent()).stream()
                .collect(Collectors.toMap(ReconciliationResolution::getEntryId, Function.identity()));
        return PageResponse.from(page.map(entry -> ReconciliationViews.entry(entry, batch.currency(),
                decided.get(entry.getId()))));
    }
}
