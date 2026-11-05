package com.xavier.backend.orders.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Every order change is audited in the caller's transaction (D20, D41) and notifies {@code ORDER} for the order after
 * commit (D60). A change made in a session (POS, or settlement's request) is audited as that actor; one made with no
 * session (production's listener, a settlement job) as the system.
 */
@Component
public class OrderChanges {

    static final String ENTITY = "Order";

    private final AuditLog auditLog;
    private final ChangeNotifier changeNotifier;

    OrderChanges(AuditLog auditLog, ChangeNotifier changeNotifier) {
        this.auditLog = auditLog;
        this.changeNotifier = changeNotifier;
    }

    public void record(String action, Order order, Map<String, Object> details) {
        record(action, ENTITY, order.getId(), order.getStoreId(), order.getId(), details);
    }

    public void record(String action, String entityType, UUID entityId, UUID storeId, UUID orderId,
                       Map<String, Object> details) {
        if (inSession()) {
            auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
        } else {
            auditLog.recordAsSystem(action, entityType, entityId.toString(), storeId, null, null, details);
        }
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.ORDER, orderId);
    }

    private static boolean inSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Actor;
    }
}
