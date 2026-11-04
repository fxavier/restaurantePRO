package com.xavier.backend.cash.service;

import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Records a cash fact in the caller's transaction (D20, D41), attributed to the current actor. */
@Component
class CashAudit {

    private final AuditLog auditLog;

    CashAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        record(action, entityType, entityId, storeId, null, null, details);
    }

    void record(String action, String entityType, UUID entityId, UUID storeId, UUID approverId, String reason,
                Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, approverId, reason, details);
    }
}
