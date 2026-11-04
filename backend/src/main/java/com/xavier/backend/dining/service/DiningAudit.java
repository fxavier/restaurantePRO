package com.xavier.backend.dining.service;

import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Records a dining configuration change in the caller's transaction (D20), attributed to the current actor. */
@Component
class DiningAudit {

    private final AuditLog auditLog;

    DiningAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
    }
}
