package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.dto.AdjustmentRequest;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections after a session closed (D79 (1); spec §8.3 "correções posteriores são movimentos auditados; não
 * reescrever contagem original"): the BackOffice records an ADJUSTMENT movement, and the stored count stays as it was.
 */
@Service
public class CashAdjustmentService {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashAdjustmentService(CashSessionRepository sessions, CashMovementRepository movements,
                          AccessPolicy accessPolicy, RowLocks locks, CashAudit audit, Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * {@code CASH_ADJUST} in the session's store (D42: read the store, authorize, then lock). The session's day is
     * locked {@code FOR UPDATE}, so the adjustment and a financial close or a closing approval of that day happen one
     * after the other: an approval that follows sees the adjustment and refuses a stale closing (D79 (2)).
     *
     * @throws com.xavier.backend.cash.exception.CashRuleViolationException 422 {@code SESSION_STILL_OPEN},
     *                                                                     {@code DAY_FINANCIALLY_CLOSED}
     */
    @Transactional
    public MovementView adjust(UUID sessionId, AdjustmentRequest request) {
        UUID storeId = sessions.findStoreIdById(sessionId)
                .orElseThrow(() -> new CashNotFoundException(CashSessionService.ENTITY, sessionId));
        accessPolicy.require(Permission.CASH_ADJUST, storeId);
        CashSession session = sessions.findById(sessionId).orElseThrow();
        CashDay day = locks.dayForUpdate(session.getCashDayId());
        session.requireClosed();
        day.requireNotFinanciallyClosed();
        Actor actor = accessPolicy.current();
        CashMovement adjustment = movements.save(CashMovement.adjustment(session, request.direction(),
                request.methodCode(), CashAmounts.of(request.amount(), session.currency()), request.reason(),
                actor.userId(), actor.kind(), clock.instant()));
        audit.record("CASH_ADJUSTMENT_RECORDED", CashMovementService.ENTITY, adjustment.getId(), storeId, null,
                request.reason().strip(), Map.of("sessionId", sessionId.toString(),
                        "direction", request.direction().name(), "methodCode", request.methodCode(),
                        "amount", request.amount().toPlainString()));
        return adjustment.toView();
    }
}
