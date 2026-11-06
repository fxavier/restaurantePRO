package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.TaxCorrectionResolution;
import com.xavier.backend.settlement.model.TaxRoundingAdjustment;
import com.xavier.backend.settlement.model.TaxRoundingPlacement;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Candidate;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Drift;
import com.xavier.backend.settlement.model.TaxRoundingPlacement.Placement;
import com.xavier.backend.settlement.model.dto.TaxRoundingAdjustmentView;
import com.xavier.backend.settlement.model.dto.TaxRoundingDifference;
import com.xavier.backend.settlement.repository.TaxCorrectionResolutionRepository;
import com.xavier.backend.settlement.repository.TaxRoundingAdjustmentRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.settlement.service.ResidualDocuments.ResidualDraft;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tax cent of settled voids (D96): placed once on the residual draft, recorded, and re-applied ever after. A
 * drifting line (a settled void emptied its pool) gets one {@link TaxRoundingAdjustment}: ABSORBED by a residual line
 * of its rate ({@link TaxRoundingPlacement}), or a PENDING_CORRECTION of the document a correction process must amend
 * when nothing can take it. A later draft of the same residual (a retried close, {@code /documents} after a credit
 * sale) re-applies the stored rows instead of placing again, so the redraft is identical and fiscal finds no
 * mismatch. Every method runs in the caller's transaction, under the order's {@code order_settlement} lock.
 */
@Component
class TaxRoundingAdjustments {

    static final String ABSORBED = "TAX_ROUNDING_ABSORBED";
    static final String PENDING = "FISCAL_CORRECTION_PENDING";

    private final TaxRoundingAdjustmentRepository adjustments;
    private final TaxCorrectionResolutionRepository resolutions;
    private final AccessPolicy accessPolicy;
    private final SettlementAudit audit;
    private final Clock clock;

    TaxRoundingAdjustments(TaxRoundingAdjustmentRepository adjustments,
                           TaxCorrectionResolutionRepository resolutions, AccessPolicy accessPolicy,
                           SettlementAudit audit, Clock clock) {
        this.adjustments = adjustments;
        this.resolutions = resolutions;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /** A residual draft with every drift's cent placed: the stored rows first, then the drifts with none yet. */
    record Placed(ResidualDraft draft, List<Placement> fresh) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Placed residual(Bill bill, Buyer buyer, List<DocumentView> documents) {
        ResidualDraft raw = ResidualDocuments.residual(bill, buyer, documents);
        Map<UUID, TaxRoundingAdjustment> stored = storedByLine(bill);
        List<Placement> reapplied = stored.values().stream().filter(TaxRoundingAdjustment::isAbsorbed)
                .map(row -> reapplied(row, raw.candidates())).toList();
        List<Candidate> candidates = TaxRoundingPlacement.apply(reapplied, raw.candidates());
        List<Drift> unrecorded = drifts(bill).stream().filter(d -> !stored.containsKey(d.sourceLineId())).toList();
        List<Placement> fresh = TaxRoundingPlacement.place(unrecorded, candidates);
        return new Placed(new ResidualDraft(bill, buyer, TaxRoundingPlacement.apply(fresh, candidates)), fresh);
    }

    /** Records the fresh placements of {@code placed} once fiscal returned its {@code residual} document. */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(Bill bill, Placed placed, DocumentView residual, List<DocumentView> documents) {
        for (Placement placement : placed.fresh()) {
            Drift drift = placement.drift();
            if (placement.absorbed()) {
                Candidate target = placed.draft().candidates().get(placement.draftLineIndex());
                save(bill, TaxRoundingAdjustment.absorbed(bill.view().orderId(), bill.storeId(),
                        bill.view().businessDay(), drift.sourceLineId(), drift.ratePercent(), drift.amount(),
                        residual.id(), target.sourceLineId(), target.partitionId(), recorder(), clock.instant()));
            } else {
                boolean onResidual = placed.draft().candidates().stream()
                        .anyMatch(c -> c.sourceLineId().equals(drift.sourceLineId()));
                pending(bill, drift, onResidual ? residual.id() : newestShareDocument(bill, drift, documents));
            }
        }
    }

    /**
     * Close s3 with no residual document (D96 rule 4): partition documents cover every quantity, so every drift
     * without a row is a pending correction of the document holding its line's newest share.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordUnplaced(Bill bill, List<DocumentView> documents) {
        Map<UUID, TaxRoundingAdjustment> stored = storedByLine(bill);
        drifts(bill).stream().filter(d -> !stored.containsKey(d.sourceLineId()))
                .forEach(d -> pending(bill, d, newestShareDocument(bill, d, documents)));
    }

    /** Per rate of the order's lines, in rate order (D96 rule 7). */
    List<TaxRoundingDifference> differences(Bill bill) {
        Money zero = Money.of(BigDecimal.ZERO, bill.due().currency());
        Map<BigDecimal, Money[]> perRate = new TreeMap<>();
        Function<BigDecimal, Money[]> rate = r -> perRate.computeIfAbsent(r.stripTrailingZeros(),
                k -> new Money[] {zero, zero, zero, zero});
        for (BillingLine line : bill.view().lines()) {
            Money[] sums = rate.apply(line.ratePercent());
            sums[0] = sums[0].plus(bill.split(line).taxDrift());
        }
        List<TaxRoundingAdjustment> rows = adjustments.findByOrderIdOrderById(bill.view().orderId());
        Set<UUID> resolved = resolvedOf(rows).keySet();
        for (TaxRoundingAdjustment row : rows) {
            Money[] sums = rate.apply(row.getRatePercent());
            int slot = row.isAbsorbed() ? 1 : resolved.contains(row.getId()) ? 3 : 2;
            sums[slot] = sums[slot].plus(row.amount());
        }
        return perRate.entrySet().stream().map(e -> new TaxRoundingDifference(e.getKey(), e.getValue()[0],
                e.getValue()[1], e.getValue()[2], e.getValue()[3])).toList();
    }

    /** The order's rows with their resolutions, oldest first. */
    List<TaxRoundingAdjustmentView> views(UUID orderId) {
        List<TaxRoundingAdjustment> rows = adjustments.findByOrderIdOrderById(orderId);
        Map<UUID, TaxCorrectionResolution> resolved = resolvedOf(rows);
        return rows.stream().map(row -> row.toView(resolved.containsKey(row.getId())
                ? resolved.get(row.getId()).toView() : null)).toList();
    }

    /** The order's drifting lines, in billing-view line order: δ = the tax left in an emptied pool. */
    static List<Drift> drifts(Bill bill) {
        List<Drift> drifts = new ArrayList<>();
        for (BillingLine line : bill.view().lines()) {
            Money drift = bill.split(line).taxDrift();
            if (drift.amount().signum() != 0) {
                drifts.add(new Drift(line.lineId(), line.ratePercent(), drift));
            }
        }
        return drifts;
    }

    private Map<UUID, TaxRoundingAdjustment> storedByLine(Bill bill) {
        return adjustments.findByOrderIdOrderById(bill.view().orderId()).stream()
                .collect(Collectors.toMap(TaxRoundingAdjustment::getSourceLineId, Function.identity()));
    }

    private Map<UUID, TaxCorrectionResolution> resolvedOf(List<TaxRoundingAdjustment> rows) {
        List<UUID> pending = rows.stream().filter(r -> !r.isAbsorbed()).map(TaxRoundingAdjustment::getId).toList();
        if (pending.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TaxCorrectionResolution> resolved = new HashMap<>();
        resolutions.findByAdjustmentIdIn(pending).forEach(r -> resolved.put(r.getAdjustmentId(), r));
        return resolved;
    }

    /** A stored ABSORBED row as a placement on the residual line it names (the draft is frozen: it is still there). */
    private static Placement reapplied(TaxRoundingAdjustment row, List<Candidate> candidates) {
        Candidate target = candidates.stream()
                .filter(c -> c.sourceLineId().equals(row.getTargetLineId())
                        && Objects.equals(c.partitionId(), row.getTargetPartitionId()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Tax rounding adjustment " + row.getId()
                        + " names a line the residual draft of order " + row.getOrderId() + " no longer has"));
        return new Placement(new Drift(row.getSourceLineId(), row.getRatePercent(), row.amount()),
                target.draftLineIndex());
    }

    /** The document of the drifting line's newest share (partition {@code created_at} desc, then id desc). */
    private static UUID newestShareDocument(Bill bill, Drift drift, List<DocumentView> documents) {
        Map<UUID, DocumentView> byPartition = SettlementViews.partitionDocuments(documents);
        LineSplit split = bill.split(drift.sourceLineId()).orElseThrow();
        return split.live().stream().map(piece -> bill.partition(piece.partitionId()).orElseThrow())
                .max(Comparator.comparing(BillPartition::getCreatedAt).thenComparing(BillPartition::getId))
                .map(p -> byPartition.get(p.getId())).map(DocumentView::id)
                .orElseThrow(() -> new IllegalStateException("Line " + drift.sourceLineId() + " of order "
                        + bill.view().orderId() + " drifts but no document holds its shares"));
    }

    private void pending(Bill bill, Drift drift, UUID documentId) {
        save(bill, TaxRoundingAdjustment.pendingCorrection(bill.view().orderId(), bill.storeId(),
                bill.view().businessDay(), drift.sourceLineId(), drift.ratePercent(), drift.amount(), documentId,
                recorder(), clock.instant()));
    }

    private void save(Bill bill, TaxRoundingAdjustment row) {
        UUID orderId = bill.view().orderId();
        UniqueConstraints.saveAndFlush(adjustments, row, () -> new IllegalStateException("Line "
                + row.getSourceLineId() + " of order " + orderId + " already has its tax rounding adjustment"),
                "tax_rounding_adjustment_line_uk");
        Map<String, Object> details = new HashMap<>();
        details.put("adjustmentId", row.getId().toString());
        details.put("sourceLineId", row.getSourceLineId().toString());
        details.put("ratePercent", row.getRatePercent().stripTrailingZeros().toPlainString());
        details.put("amount", row.amount().amount().toPlainString());
        details.put("documentId", row.getDocumentId().toString());
        if (row.getTargetLineId() != null) {
            details.put("targetLineId", row.getTargetLineId().toString());
        }
        if (row.getTargetPartitionId() != null) {
            details.put("targetPartitionId", row.getTargetPartitionId().toString());
        }
        audit.orderFact(row.isAbsorbed() ? ABSORBED : PENDING, orderId, bill.storeId(), details);
    }

    private TaxRoundingAdjustment.Recorder recorder() {
        return accessPolicy.currentIfPresent().map(TaxRoundingAdjustments::recorder)
                .orElse(new TaxRoundingAdjustment.Recorder("SYSTEM", null));
    }

    static TaxRoundingAdjustment.Recorder recorder(Actor actor) {
        return new TaxRoundingAdjustment.Recorder(actor.kind().name(), actor.userId());
    }
}
