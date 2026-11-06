package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every order change is audited in the caller's transaction (D20, D41) and notifies {@code ORDER} for the order after
 * commit (D60). A change made in a session (POS, or settlement's request) is audited as that actor; one made with no
 * session (production's listener, a settlement job) as the system.
 */
@Component
public class OrderChanges {

    static final String ENTITY = "Order";

    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ChangeNotifier changeNotifier;

    OrderChanges(AccessPolicy accessPolicy, AuditLog auditLog, ChangeNotifier changeNotifier) {
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.changeNotifier = changeNotifier;
    }

    public void record(String action, Order order, Map<String, Object> details) {
        record(action, ENTITY, order.getId(), order.getStoreId(), order.getId(), details);
    }

    public void record(String action, String entityType, UUID entityId, UUID storeId, UUID orderId,
                       Map<String, Object> details) {
        if (accessPolicy.currentIfPresent().isPresent()) {
            auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
        } else {
            auditLog.recordAsSystem(action, entityType, entityId.toString(), storeId, null, null, details);
        }
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.ORDER, orderId);
    }

    /** Notifies {@code ORDER} for an order whose change another record audited (the target of a transfer). */
    public void notify(Order order) {
        changeNotifier.notifyAfterCommit(order.getStoreId(), ChangeTopic.ORDER, order.getId());
    }

    /**
     * A POS change with its reason and, when one authorized it, its approver (D28, D40; {@code null} otherwise);
     * always made in a session.
     */
    public void recordWithReason(String action, String entityType, UUID entityId, Order order, UUID approverId,
                               String reason, Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), order.getStoreId(), approverId, reason,
                details);
        changeNotifier.notifyAfterCommit(order.getStoreId(), ChangeTopic.ORDER, order.getId());
    }
}
