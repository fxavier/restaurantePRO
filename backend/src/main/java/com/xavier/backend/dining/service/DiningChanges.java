package com.xavier.backend.dining.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every dining change changes the POS table map: it is audited as the current actor in the caller's transaction
 * (D20, D41) and notifies {@code TABLES} for its store after commit (D60).
 */
@Component
public class DiningChanges {

    private final AuditLog auditLog;
    private final ChangeNotifier changeNotifier;

    DiningChanges(AuditLog auditLog, ChangeNotifier changeNotifier) {
        this.auditLog = auditLog;
        this.changeNotifier = changeNotifier;
    }

    /** @param changedId the changed entity; also the notice id (the table, area or occupation's table) */
    public void record(String action, String entityType, UUID entityId, UUID storeId, UUID changedId,
                       Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.TABLES, changedId);
    }

    public void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        record(action, entityType, entityId, storeId, entityId, details);
    }
}
