package com.xavier.backend.identity;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.service.ApprovalService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Supervisor approval (D28, spec §3 "Aprovações críticas identificam operador e aprovador"): a critical action
 * (void, discount, reopen…) carries a second employee's code + PIN, and the use case asks this before acting.
 * Public API of the identity module; first used in R2.
 *
 * <p>Usage, inside the caller's use case, after its own {@code accessPolicy.require(...)}:
 * <pre>{@code
 * UUID approverId = approvers.verify(request.approverCode(), request.approverPin(), Permission.ORDER_VOID, storeId);
 * auditLog.record(new AuditEntry(actor.userId(), approverId, "ORDER_LINE_VOIDED", ...));
 * }</pre>
 *
 * <p>A wrong PIN counts toward the approver's lockout (5 → locked, audited) even when the caller's transaction
 * then rolls back: the check commits on its own. The approver must be an active, unlocked employee assigned to
 * {@code storeId} with a role holding {@code permission} there. Whether the approver may be the operator is the
 * caller's rule.
 */
@Service
public class ApproverVerifier {

    private final ApprovalService approvals;

    ApproverVerifier(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /**
     * @return the approver's employee id, for the audit row's {@code approverId}
     * @throws ApprovalDeniedException (403) for any refusal; the reason is logged at WARN, never the PIN
     */
    public UUID verify(String employeeCode, String pin, Permission permission, UUID storeId) {
        return approvals.verify(employeeCode, pin, permission, storeId);
    }
}
