package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.MovementType;
import com.xavier.backend.cash.model.dto.ManualMovementRequest;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cash in, cash out and authorized expenses at the POS (spec §4 "entradas, retiradas, despesas autorizadas"). */
@Service
public class CashMovementService {

    static final String ENTITY = "CashMovement";

    private static final Logger log = LoggerFactory.getLogger(CashMovementService.class);

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashMovementService(CashSessionRepository sessions, CashMovementRepository movements, AccessPolicy accessPolicy,
                        RowLocks locks, CashAudit audit, Clock clock) {
        this.sessions = sessions;
        this.locks = locks;
        this.movements = movements;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records the movement in the operator's own OPEN session, read {@code FOR SHARE} so a concurrent close either
     * waits for it or refuses it (422 {@code SESSION_NOT_OPEN}). An expense also needs {@code CASH_EXPENSE}.
     */
    @Transactional
    public MovementView record(ManualMovementRequest request) {
        MovementType type = request.type().toType();
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        if (type == MovementType.EXPENSE) {
            accessPolicy.requirePos(Permission.CASH_EXPENSE);
        }
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
        audit.record("CASH_MOVEMENT_RECORDED", ENTITY, movement.getId(), movement.getStoreId(), null,
                request.reason().strip(), Map.of("type", type.name(), "sessionId", session.getId().toString(),
                        "amount", request.amount().toPlainString()));
        return movement.toView();
    }
}
