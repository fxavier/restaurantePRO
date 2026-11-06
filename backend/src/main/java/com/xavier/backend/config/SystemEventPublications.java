package com.xavier.backend.config;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.shared.events.EventPublicationRecovery;
import com.xavier.backend.shared.events.EventPublicationState;
import com.xavier.backend.shared.events.EventPublicationStats;
import com.xavier.backend.shared.events.EventPublicationView;
import com.xavier.backend.shared.web.PageResponse;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The BackOffice use cases on the event publication registry (D88). Installation-wide {@code SYSTEM_MONITOR}: the
 * registry spans every store. A resubmission runs outside any transaction (Modulith claims each publication and the
 * listeners run asynchronously), then is audited with no store in its own transaction, with what it selected.
 */
@Service
class SystemEventPublications {

    static final String AUDIT_ACTION = "EVENT_PUBLICATIONS_RESUBMITTED";

    private final AccessPolicy accessPolicy;
    private final EventPublicationStats stats;
    private final EventPublicationRecovery recovery;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    SystemEventPublications(AccessPolicy accessPolicy, EventPublicationStats stats, EventPublicationRecovery recovery,
                            AuditLog auditLog, TransactionTemplate transaction) {
        this.accessPolicy = accessPolicy;
        this.stats = stats;
        this.recovery = recovery;
        this.auditLog = auditLog;
        this.transaction = transaction;
    }

    PageResponse<EventPublicationView> list(EventPublicationState state, Pageable pageable) {
        accessPolicy.require(Permission.SYSTEM_MONITOR);
        return stats.list(state, pageable);
    }

    ResubmissionView resubmit(ResubmitRequest request) {
        accessPolicy.require(Permission.SYSTEM_MONITOR);
        boolean byIds = request.olderThan() == null;
        int selected = byIds ? recovery.resubmit(request.publicationIds())
                : recovery.resubmitOlderThan(request.olderThan());
        Map<String, Object> details = byIds
                ? Map.of("publicationIds", request.publicationIds().stream().map(Object::toString).toList(),
                        "selected", selected)
                : Map.of("olderThan", request.olderThan().toString(), "selected", selected);
        transaction.executeWithoutResult(tx -> auditLog.recordAsCurrentActor(AUDIT_ACTION, "EventPublicationRegistry",
                "event_publication", null, null, null, details));
        return new ResubmissionView(selected);
    }
}
