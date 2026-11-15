package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationBatch;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import com.xavier.backend.settlement.reconciliation.model.dto.BatchView;
import com.xavier.backend.settlement.reconciliation.model.dto.EntryView;
import com.xavier.backend.settlement.reconciliation.model.dto.InDoubtAttemptView;
import com.xavier.backend.settlement.reconciliation.repository.KindCount;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationBatchRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationEntryRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationResolutionRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** D81: the attempts a person may have to reconcile by hand. */
    static final Set<AttemptStatus> IN_DOUBT = Set.of(AttemptStatus.PENDING, AttemptStatus.UNKNOWN);

    private final StoreProbe storeProbe;
    private final AccessPolicy accessPolicy;
    private final ReconciliationBatchRepository batches;
    private final ReconciliationEntryRepository entries;
    private final ReconciliationResolutionRepository resolutions;
    private final PaymentAttemptRepository attempts;
    private final Clock clock;

    ReconciliationQueryService(StoreProbe storeProbe, AccessPolicy accessPolicy, ReconciliationBatchRepository batches,
                               ReconciliationEntryRepository entries,
                               ReconciliationResolutionRepository resolutions, PaymentAttemptRepository attempts,
                               Clock clock) {
        this.storeProbe = storeProbe;
        this.accessPolicy = accessPolicy;
        this.batches = batches;
        this.entries = entries;
        this.resolutions = resolutions;
        this.attempts = attempts;
        this.clock = clock;
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
        UUID storeId = storeProbe.storeOf("settlement.reconciliation_batch", batchId)
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

    /** The store's PENDING and UNKNOWN attempts, oldest first, with their age and what the provider answered. */
    @Transactional(readOnly = true)
    public List<InDoubtAttemptView> inDoubt(UUID storeId) {
        accessPolicy.require(Permission.RECONCILIATION_VIEW, storeId);
        Instant now = clock.instant();
        return attempts.findByStoreIdAndStatusInOrderByCreatedAt(storeId, IN_DOUBT).stream()
                .map(attempt -> view(attempt, now)).toList();
    }

    private static InDoubtAttemptView view(PaymentAttempt attempt, Instant now) {
        return new InDoubtAttemptView(attempt.getId(), attempt.getOrderId(), attempt.getPartitionId(),
                attempt.getCreditAccountId(), attempt.getMethodCode(), attempt.getMethodKind().name(),
                attempt.getStatus().name(), attempt.amount(), attempt.getCreatedAt(),
                Duration.between(attempt.getCreatedAt(), now).toSeconds(), attempt.getPollCount(),
                attempt.getAnsweredCount(), attempt.getLastPollAnswer(), attempt.getLastPolledAt(),
                attempt.getNextPollAt());
    }
}
