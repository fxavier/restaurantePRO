package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.MovementType;
import com.xavier.backend.cash.model.dto.ManualMovementRequest;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash in, cash out and authorized expenses at the POS (spec §4 "entradas, retiradas, despesas autorizadas"); an
 * expense is authorized by a second employee (P24).
 */
@Service
public class CashMovementService {

    static final String ENTITY = "CashMovement";

    private static final Logger log = LoggerFactory.getLogger(CashMovementService.class);

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final AccessPolicy accessPolicy;
    private final ApproverVerifier approvers;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashMovementService(CashSessionRepository sessions, CashMovementRepository movements, AccessPolicy accessPolicy,
                        ApproverVerifier approvers, RowLocks locks, CashAudit audit, Clock clock) {
        this.approvers = approvers;
        this.sessions = sessions;
        this.locks = locks;
        this.movements = movements;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Phase 1 (D40), outside any transaction: {@code CASH_OPERATE} at the POS, and for an expense (P24) an approver
     * holding {@code CASH_EXPENSE} in the POS store who is not the operator. A wrong PIN counts toward the approver's
     * lockout.
     *
     * @return the approver's employee id, or null for a movement that needs none
     * @throws ApprovalDeniedException     403 for a missing or refused approver
     * @throws CashRuleViolationException 422 {@code SELF_APPROVAL}
     */
    public UUID authorize(ManualMovementRequest request, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        if (request.type().toType() != MovementType.EXPENSE) {
            return null;
        }
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        UUID approverId = approvers.verify(approverCode, approverPin, Permission.CASH_EXPENSE, pos.storeId());
        if (approverId.equals(pos.employeeId())) {
            throw new CashRuleViolationException(CashRule.SELF_APPROVAL,
                    "The approver must be another employee than the operator.");
        }
        return approverId;
    }

    /**
     * Phase 2: records the movement in the operator's own OPEN session, read {@code FOR SHARE} so a concurrent close
     * either waits for it or refuses it (422 {@code SESSION_NOT_OPEN}). An expense carries its approver from phase 1
     * into the audit row.
     */
    @Transactional
    public MovementView record(ManualMovementRequest request, UUID approverEmployeeId) {
        MovementType type = request.type().toType();
        if ((type == MovementType.EXPENSE) != (approverEmployeeId != null)) {
            throw new IllegalArgumentException("An expense, and only an expense, has an approver");
        }
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        UUID sessionStore = sessions.findStoreIdById(request.sessionId())
                .orElseThrow(() -> new CashNotFoundException(CashSessionService.ENTITY, request.sessionId()));
        StoreScope.requireSameStore(pos.storeId(), sessionStore, CashSessionService.ENTITY, request.sessionId());
        CashSession session = locks.sessionForShare(request.sessionId());
        if (!session.getOperatorEmployeeId().equals(pos.employeeId())) {
            log.warn("Access denied: employee {} wrote to cash session {} of employee {}", pos.employeeId(),
                    session.getId(), session.getOperatorEmployeeId());
            throw new AccessDeniedException("Cash session " + session.getId() + " belongs to another operator");
        }
        CashMovement movement = movements.save(CashMovement.manual(session, type,
                CashAmounts.of(request.amount(), session.currency()), request.reason(), pos.employeeId(),
                clock.instant()));
        audit.record("CASH_MOVEMENT_RECORDED", ENTITY, movement.getId(), movement.getStoreId(), approverEmployeeId,
                request.reason().strip(), Map.of("type", type.name(), "sessionId", session.getId().toString(),
                        "amount", request.amount().toPlainString()));
        return movement.toView();
    }
}
