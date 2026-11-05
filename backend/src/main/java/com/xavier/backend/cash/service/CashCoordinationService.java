package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.ReceiptAlreadyRecordedException;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashDayStatus;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.CashSessionCount;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashSessionCountRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cash steps settlement composes into its coordinated use cases (D36, D37), behind
 * {@link com.xavier.backend.cash.CashApi}. Writes join the caller's transaction ({@code MANDATORY}) and do not
 * authorize: the settlement use case already did (D27). Locks follow D51 (order_settlement → cash_session).
 */
@Service
public class CashCoordinationService {

    private final CashSessionRepository sessions;
    private final CashSessionCountRepository counts;
    private final CashMovementRepository movements;
    private final CashDayRepository days;
    private final RowLocks locks;
    private final AccessPolicy accessPolicy;
    private final CashAudit audit;
    private final Clock clock;

    CashCoordinationService(CashSessionRepository sessions, CashSessionCountRepository counts,
                            CashMovementRepository movements, CashDayRepository days, RowLocks locks,
                            AccessPolicy accessPolicy, CashAudit audit, Clock clock) {
        this.sessions = sessions;
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.counts = counts;
        this.movements = movements;
        this.days = days;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<OpenSessionView> openSessionOf(UUID storeId, UUID employeeId) {
        return sessions.findByStoreIdAndOperatorEmployeeIdAndStatus(storeId, employeeId, CashSessionStatus.OPEN)
                .map(CashSession::toOpenView);
    }

    /**
     * The payment's movement: the recorded one if the payment was recorded before (even if its session has closed
     * since), else a new one in the OPEN session, read {@code FOR SHARE} against a concurrent close.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView recordReceipt(ReceiptCommand command) {
        Optional<CashMovement> recorded = movements.findBySourceTypeAndSourceId(CashMovement.PAYMENT_SOURCE,
                command.paymentId());
        if (recorded.isPresent()) {
            return recorded.get().toView();
        }
        CashSession session = locks.sessionForShare(command.sessionId());
        CashMovement receipt = CashMovement.receipt(session, command.methodCode(), command.amount(),
                command.paymentId(), command.actorEmployeeId(), clock.instant());
        return UniqueConstraints.saveAndFlush(movements, receipt,
                () -> new ReceiptAlreadyRecordedException(command.paymentId()), "cash_movement_source_uk").toView();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockSession(UUID sessionId) {
        return sessionForUpdate(sessionId).toView();
    }

    /** OPEN → CLOSED with the blind count (spec §8.3); settlement has checked that no attempt is in doubt. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeSession(UUID sessionId, long expectedVersion, List<MethodCount> blindCount,
                             UUID actorEmployeeId) {
        requireCurrentActor(actorEmployeeId, ActorKind.EMPLOYEE);
        CashSession session = sessionForUpdate(sessionId);
        OptimisticLocking.requireVersion(CashSession.class, sessionId, session.getVersion(), expectedVersion);
        List<CashSessionCount> recorded = session.close(blindCount, actorEmployeeId, clock.instant());
        counts.saveAll(recorded);
        sessions.saveAndFlush(session);
        audit.record("CASH_SESSION_CLOSED", CashSessionService.ENTITY, sessionId, session.getStoreId(),
                Map.of("counts", blindCount.stream().map(c -> Map.of("methodCode", c.methodCode(),
                        "amount", c.amount().amount().toPlainString())).toList()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DayView lockDay(UUID storeId, LocalDate businessDate) {
        CashDay day = days.findByStoreIdAndBusinessDate(storeId, businessDate)
                .orElseThrow(() -> new CashNotFoundException(CashDayService.ENTITY, storeId + "/" + businessDate));
        return locks.lock(day, LockModeType.PESSIMISTIC_WRITE).toView();
    }

    @Transactional(readOnly = true)
    public UUID storeOfDay(UUID dayId) {
        return CashDayService.storeOf(days, dayId);
    }

    @Transactional(readOnly = true)
    public Optional<DayView> findDay(UUID dayId) {
        return days.findById(dayId).map(CashDay::toView);
    }

    /**
     * OPEN → OPERATIONALLY_CLOSED under the day's lock (D50 §7.8). Cash checks its own part, every session CLOSED;
     * settlement has checked orders, attempts and fiscal drafts.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeDayOperationally(UUID dayId, long expectedVersion, UUID actorId, ActorKind kind) {
        requireCurrentActor(actorId, kind);
        CashDay day = locks.dayForUpdate(dayId);
        OptimisticLocking.requireVersion(CashDay.class, dayId, day.getVersion(), expectedVersion);
        if (day.getStatus() == CashDayStatus.OPEN
                && sessions.existsByCashDayIdAndStatus(dayId, CashSessionStatus.OPEN)) {
            throw new CashRuleViolationException(CashRule.OPEN_SESSIONS,
                    "The cash day " + day.getBusinessDate() + " still has open sessions.");
        }
        Instant now = clock.instant();
        day.closeOperationally(actorId, kind, now);
        days.saveAndFlush(day);
        audit.record("CASH_DAY_OPERATIONALLY_CLOSED", CashDayService.ENTITY, dayId, day.getStoreId(),
                Map.of("businessDate", day.getBusinessDate().toString()));
    }

    @Transactional(readOnly = true)
    public List<UUID> openSessionIds(UUID dayId) {
        return sessions.findIdsByCashDayIdAndStatus(dayId, CashSessionStatus.OPEN);
    }

    /** The fact's actor column and its audit row (written as the current actor, D41) must name the same person. */
    private void requireCurrentActor(UUID actorId, ActorKind kind) {
        Actor current = accessPolicy.current();
        if (!current.userId().equals(actorId) || current.kind() != kind) {
            throw new IllegalArgumentException("The actor " + kind + " " + actorId + " is not the current actor "
                    + current.kind() + " " + current.userId());
        }
    }

    private CashSession sessionForUpdate(UUID sessionId) {
        return locks.sessionForUpdate(sessionId);
    }
}
