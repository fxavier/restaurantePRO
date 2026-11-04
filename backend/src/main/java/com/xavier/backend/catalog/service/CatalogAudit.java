package com.xavier.backend.catalog.service;

import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Every catalog write is audited in the use case's transaction (D20), with the current actor. */
@Component
class CatalogAudit {

    private final AuditLog auditLog;

    CatalogAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** {@code storeId} is null for company-wide entries (families, items, …). */
    void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
    }
}
