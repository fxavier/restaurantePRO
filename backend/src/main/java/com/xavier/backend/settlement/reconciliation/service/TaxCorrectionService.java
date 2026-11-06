package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.settlement.model.TaxCorrectionResolution;
import com.xavier.backend.settlement.model.TaxRoundingAdjustment;
import com.xavier.backend.settlement.model.dto.TaxRoundingAdjustmentView;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationConflictException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationRuleException;
import com.xavier.backend.settlement.reconciliation.model.dto.TaxCorrectionRequest;
import com.xavier.backend.settlement.repository.TaxCorrectionResolutionRepository;
import com.xavier.backend.settlement.repository.TaxRoundingAdjustmentRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pending fiscal corrections (D96 rules 4 and 6): a store's list with {@code RECONCILIATION_VIEW}, and their one
 * resolution with {@code RECONCILIATION_MANAGE} in the adjustment's store, audited. A resolution records that the
 * market's correction process produced a corrective document; it changes no document and no money.
 */
@Service
public class TaxCorrectionService {

    static final String ENTITY = "TaxCorrection";
    static final String RESOLVED = "TAX_CORRECTION_RESOLVED";

    private final AccessPolicy accessPolicy;
    private final OrganizationApi organization;
    private final TaxRoundingAdjustmentRepository adjustments;
    private final TaxCorrectionResolutionRepository resolutions;
    private final AuditLog audit;
    private final Clock clock;

    TaxCorrectionService(AccessPolicy accessPolicy, OrganizationApi organization,
                         TaxRoundingAdjustmentRepository adjustments, TaxCorrectionResolutionRepository resolutions,
                         AuditLog audit, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
        this.adjustments = adjustments;
        this.resolutions = resolutions;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param open true: unresolved only; false: resolved only; null: every pending correction of the store */
    @Transactional(readOnly = true)
    public PageResponse<TaxRoundingAdjustmentView> pending(UUID storeId, Boolean open, Pageable pageable) {
        accessPolicy.require(Permission.RECONCILIATION_VIEW, storeId);
        if (!organization.storeExists(storeId)) {
            throw new ReconciliationNotFoundException("Store", storeId);
        }
        Page<TaxRoundingAdjustment> page = adjustments.pendingOf(storeId, open, pageable);
        Map<UUID, TaxCorrectionResolution> resolved = resolutions.findByAdjustmentIdIn(
                        page.map(TaxRoundingAdjustment::getId).getContent()).stream()
                .collect(Collectors.toMap(TaxCorrectionResolution::getAdjustmentId, Function.identity()));
        return PageResponse.from(page.map(row -> row.toView(resolved.containsKey(row.getId())
                ? resolved.get(row.getId()).toView() : null)));
    }

    /**
     * @throws ReconciliationRuleException 422 {@code NOT_A_PENDING_CORRECTION} for an ABSORBED cent
     * @throws ReconciliationConflictException 409 {@code TAX_CORRECTION_RESOLVED}, even for a concurrent second
     */
    @Transactional
    public TaxRoundingAdjustmentView resolve(UUID adjustmentId, TaxCorrectionRequest request) {
        UUID storeId = adjustments.findStoreIdById(adjustmentId)
                .orElseThrow(() -> new ReconciliationNotFoundException("TaxRoundingAdjustment", adjustmentId));
        accessPolicy.require(Permission.RECONCILIATION_MANAGE, storeId);
        TaxRoundingAdjustment adjustment = adjustments.findById(adjustmentId).orElseThrow();
        if (adjustment.isAbsorbed()) {
            throw ReconciliationRuleException.notAPendingCorrection(adjustmentId);
        }
        if (resolutions.existsByAdjustmentId(adjustmentId)) {
            throw ReconciliationConflictException.taxCorrectionResolved(adjustmentId);
        }
        Actor actor = accessPolicy.current();
        TaxCorrectionResolution resolution = UniqueConstraints.saveAndFlush(resolutions,
                TaxCorrectionResolution.corrected(adjustment, request.correctionReference(), request.reason(),
                        new TaxRoundingAdjustment.Recorder(actor.kind().name(), actor.userId()), clock.instant()),
                () -> ReconciliationConflictException.taxCorrectionResolved(adjustmentId),
                "tax_correction_resolution_adjustment_uk");
        audit.recordAsCurrentActor(RESOLVED, ENTITY, adjustmentId.toString(), storeId, null, resolution.getReason(),
                Map.of("orderId", adjustment.getOrderId().toString(),
                        "documentId", adjustment.getDocumentId().toString(),
                        "correctionReference", resolution.getCorrectionReference(),
                        "amount", adjustment.amount().amount().toPlainString()));
        return adjustment.toView(resolution.toView());
    }
}
