package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.dto.AdjustmentRequest;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionCountRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import java.time.Clock;
import java.util.Locale;
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

    private final CashStoreProbe stores;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashSessionCountRepository counts;
    private final ClosingRounds rounds;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashAdjustmentService(CashStoreProbe stores, CashSessionRepository sessions, CashMovementRepository movements,
                          CashSessionCountRepository counts, ClosingRounds rounds, AccessPolicy accessPolicy,
                          RowLocks locks, CashAudit audit, Clock clock) {
        this.stores = stores;
        this.counts = counts;
        this.rounds = rounds;
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
     * @throws CashRuleViolationException 422 {@code SESSION_STILL_OPEN}, {@code DAY_FINANCIALLY_CLOSED},
     *                                    {@code CLOSING_ALREADY_APPROVED}, {@code UNKNOWN_METHOD}
     */
    @Transactional
    public MovementView adjust(UUID sessionId, AdjustmentRequest request) {
        UUID storeId = stores.session(sessionId)
                .orElseThrow(() -> new CashNotFoundException(CashSessionService.ENTITY, sessionId));
        accessPolicy.require(Permission.CASH_ADJUST, storeId);
        CashSession session = sessions.findById(sessionId).orElseThrow();
        CashDay day = locks.dayForUpdate(session.getCashDayId());
        session.requireClosed();
        day.requireNotFinanciallyClosed();
        if (rounds.approvedInCurrentRound(session.getRegisterId(), day.getId())) {
            throw new CashRuleViolationException(CashRule.CLOSING_ALREADY_APPROVED,
                    "The closing of this register for " + day.getBusinessDate() + " is approved: close the day "
                            + "financially, reopen it, and adjust in the next round.");
        }
        String methodCode = knownMethod(session, request.methodCode());
        Actor actor = accessPolicy.current();
        CashMovement adjustment = movements.save(CashMovement.adjustment(session, request.direction(),
                methodCode, CashAmounts.of(request.amount(), session.currency()), request.reason(),
                actor.userId(), actor.kind(), clock.instant()));
        audit.record("CASH_ADJUSTMENT_RECORDED", CashMovementService.ENTITY, adjustment.getId(), storeId, null,
                request.reason().strip(), Map.of("sessionId", sessionId.toString(),
                        "direction", request.direction().name(), "methodCode", methodCode,
                        "amount", request.amount().toPlainString()));
        return adjustment.toView();
    }

    /**
     * Cash cannot see settlement's payment methods (D35), so a known code is one the session moved or counted; the
     * code is compared in upper case, as settlement's codes are.
     */
    private String knownMethod(CashSession session, String requested) {
        String methodCode = requested.strip().toUpperCase(Locale.ROOT);
        if (!movements.existsBySessionIdAndMethodCode(session.getId(), methodCode)
                && !counts.existsBySessionIdAndMethodCode(session.getId(), methodCode)) {
            throw new CashRuleViolationException(CashRule.UNKNOWN_METHOD,
                    "The cash session " + session.getId() + " has no " + methodCode + " movement or count.");
        }
        return methodCode;
    }
}
