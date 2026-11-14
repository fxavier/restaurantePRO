package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationConflictException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import com.xavier.backend.settlement.reconciliation.model.ResolutionAction;
import com.xavier.backend.settlement.reconciliation.model.dto.ResolutionRequest;
import com.xavier.backend.settlement.reconciliation.model.dto.ResolutionView;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationEntryRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationResolutionRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's decision about a reconciliation entry (D78), with {@code RECONCILIATION_MANAGE} in its store, audited.
 * One per entry ({@code UNIQUE(entry_id)}: a second, even concurrent, is 409 {@code ENTRY_RESOLVED}). It records the
 * decision only: a correction of money is its own command.
 */
@Service
public class ResolutionService {

    private final StoreProbe storeProbe;
    private final AccessPolicy accessPolicy;
    private final ReconciliationEntryRepository entries;
    private final ReconciliationResolutionRepository resolutions;
    private final AuditLog audit;
    private final Clock clock;

    ResolutionService(StoreProbe storeProbe, AccessPolicy accessPolicy, ReconciliationEntryRepository entries,
                      ReconciliationResolutionRepository resolutions, AuditLog audit, Clock clock) {
        this.storeProbe = storeProbe;
        this.accessPolicy = accessPolicy;
        this.entries = entries;
        this.resolutions = resolutions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ResolutionView resolve(UUID entryId, ResolutionRequest request) {
        UUID storeId = storeProbe.storeOf("settlement.reconciliation_entry", entryId)
                .orElseThrow(() -> new ReconciliationNotFoundException("ReconciliationEntry", entryId));
        accessPolicy.require(Permission.RECONCILIATION_MANAGE, storeId);
        if (resolutions.existsByEntryId(entryId)) {
            throw ReconciliationConflictException.entryResolved(entryId);
        }
        ReconciliationEntry entry = entries.findById(entryId).orElseThrow();
        Actor actor = accessPolicy.current();
        ReconciliationResolution resolution = UniqueConstraints.saveAndFlush(resolutions,
                ReconciliationResolution.by(entryId, ResolutionAction.valueOf(request.action()), request.reason(),
                        actor.kind().name(), actor.userId(), clock.instant()),
                () -> ReconciliationConflictException.entryResolved(entryId), "reconciliation_resolution_entry_uk");
        Map<String, Object> details = new HashMap<>();
        details.put("action", resolution.getAction().name());
        details.put("kind", entry.getKind().name());
        details.put("batchId", entry.getBatchId().toString());
        if (entry.getPaymentId() != null) {
            details.put("paymentId", entry.getPaymentId().toString());
        }
        audit.recordAsCurrentActor("RECONCILIATION_ENTRY_RESOLVED", "ReconciliationEntry", entryId.toString(),
                storeId, null, resolution.getReason(), details);
        return ReconciliationViews.resolution(resolution);
    }
}
