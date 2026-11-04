package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Records a dining configuration change in the caller's transaction (D20), attributed to the current actor. */
@Component
class DiningAudit {

    private final AuditLog auditLog;
    private final AccessPolicy accessPolicy;

    DiningAudit(AuditLog auditLog, AccessPolicy accessPolicy) {
        this.auditLog = auditLog;
        this.accessPolicy = accessPolicy;
    }

    void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        Actor actor = accessPolicy.current();
        auditLog.record(new AuditEntry(actor.userId(), null, action, entityType, entityId.toString(), storeId,
                actor.terminalId(), null, details));
    }
}
