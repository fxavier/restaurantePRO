package com.xavier.backend.audit;

import com.xavier.backend.audit.model.AuditEvent;
import com.xavier.backend.audit.repository.AuditEventRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit trail's only write path (D20). Call it from a use case, inside that use case's
 * transaction, so the change and its audit row commit or roll back together.
 */
@Service
public class AuditLog {

    private final AuditEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    AuditLog(AuditEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        events.save(new AuditEvent(clock.instant(), entry.actorId(), entry.approverId(), entry.action(),
                entry.entityType(), entry.entityId(), entry.storeId(), entry.terminalId(), entry.reason(),
                json.writeValueAsString(entry.details())));
    }
}
