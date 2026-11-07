package com.xavier.backend.promotions.service;

import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Every promotions write is audited in the use case's transaction (D20, D41), with the current actor. */
@Component
class PromotionsAudit {

    private final AuditLog auditLog;

    PromotionsAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** {@code storeId} is null for a company-wide promotion. */
    void record(String action, String entityType, Object entityId, UUID storeId, Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
    }
}
