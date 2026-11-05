package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every settlement fact is audited in its transaction (D20, D41): as the current actor in a request, as the system
 * from the payment submitter and the sweeper. A change to an attempt notifies {@code PAYMENT_ATTEMPT} and its order
 * {@code ORDER} after commit (D60), with ids only.
 */
@Component
class SettlementAudit {

    static final String ATTEMPT = "PaymentAttempt";
    static final String CHARGE = "CreditCharge";
    static final String REFUND = "Refund";

    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ChangeNotifier changeNotifier;

    SettlementAudit(AccessPolicy accessPolicy, AuditLog auditLog, ChangeNotifier changeNotifier) {
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.changeNotifier = changeNotifier;
    }

    void attempt(String action, UUID attemptId, UUID storeId, UUID orderId, UUID terminalId, UUID approverId,
                 String reason, Map<String, Object> details) {
        if (accessPolicy.currentIfPresent().isPresent()) {
            auditLog.recordAsCurrentActor(action, ATTEMPT, attemptId.toString(), storeId, approverId, reason,
                    details);
        } else {
            auditLog.recordAsSystem(action, ATTEMPT, attemptId.toString(), storeId, terminalId, reason, details);
        }
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.PAYMENT_ATTEMPT, attemptId);
        if (orderId != null) { // a debt payment has no order (D95: it notifies PAYMENT_ATTEMPT only)
            changeNotifier.notifyAfterCommit(storeId, ChangeTopic.ORDER, orderId);
        }
    }

    /** A credit charge's transition: as the actor in a request, as the system from the sweeper; D95 {@code ORDER}. */
    void charge(String action, UUID chargeId, UUID storeId, UUID orderId, UUID terminalId, String reason,
                Map<String, Object> details) {
        if (accessPolicy.currentIfPresent().isPresent()) {
            auditLog.recordAsCurrentActor(action, CHARGE, chargeId.toString(), storeId, null, reason, details);
        } else {
            auditLog.recordAsSystem(action, CHARGE, chargeId.toString(), storeId, terminalId, reason, details);
        }
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.ORDER, orderId);
    }

    /** D77: a refund, as the refunder with its approver and reason; D95 {@code ORDER} for an order's payment. */
    void refund(com.xavier.backend.settlement.model.Refund refund, Map<String, Object> details) {
        auditLog.recordAsCurrentActor("PAYMENT_REFUNDED", REFUND, refund.getId().toString(), refund.getStoreId(),
                refund.getApproverEmployeeId(), refund.getReason(), details);
        if (refund.getOrderId() != null) {
            changeNotifier.notifyAfterCommit(refund.getStoreId(), ChangeTopic.ORDER, refund.getOrderId());
        }
    }

    void record(String action, String entityType, UUID entityId, UUID storeId, Map<String, Object> details) {
        auditLog.recordAsCurrentActor(action, entityType, entityId.toString(), storeId, null, null, details);
    }

    /** D95: partitions, partition documents and settled voids say "re-read the order" on its {@code ORDER} topic. */
    void orderChanged(UUID storeId, UUID orderId) {
        changeNotifier.notifyAfterCommit(storeId, ChangeTopic.ORDER, orderId);
    }
}
