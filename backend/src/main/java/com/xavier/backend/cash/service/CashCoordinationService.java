package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.cash.RefundCommand;
import com.xavier.backend.cash.SessionDay;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.ReceiptAlreadyRecordedException;
import com.xavier.backend.cash.exception.RefundMismatchException;
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

    private final CashStoreProbe stores;
    private final CashSessionRepository sessions;
    private final CashSessionCountRepository counts;
    private final CashMovementRepository movements;
    private final CashDayRepository days;
    private final RowLocks locks;
    private final AccessPolicy accessPolicy;
    private final CashAudit audit;
    private final Clock clock;

    CashCoordinationService(CashStoreProbe stores, CashSessionRepository sessions, CashSessionCountRepository counts,
                            CashMovementRepository movements, CashDayRepository days, RowLocks locks,
                            AccessPolicy accessPolicy, CashAudit audit, Clock clock) {
        this.stores = stores;
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

    /**
     * As {@link #recordReceipt}, for a confirmed Refund: one {@code REFUND} OUT movement per refund (A23). A repeat
     * must carry the same session, method and amount (409 {@code REFUND_MISMATCH} otherwise).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView recordRefund(RefundCommand command) {
        Optional<CashMovement> recorded = movements.findBySourceTypeAndSourceId(CashMovement.REFUND_SOURCE,
                command.refundId());
        if (recorded.isPresent()) {
            MovementView view = recorded.get().toView();
            if (!view.sessionId().equals(command.sessionId()) || !view.methodCode().equals(command.methodCode())
                    || !view.amount().currency().equals(command.amount().currency())
                    || view.amount().amount().compareTo(command.amount().amount()) != 0) {
                throw new RefundMismatchException(command.refundId());
            }
            return view;
        }
        CashSession session = locks.sessionForShare(command.sessionId());
        CashMovement refund = CashMovement.refund(session, command.methodCode(), command.amount(),
                command.refundId(), command.actorEmployeeId(), clock.instant());
        return UniqueConstraints.saveAndFlush(movements, refund,
                () -> new ReceiptAlreadyRecordedException("refund", command.refundId()),
                "cash_movement_source_uk").toView();
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
                Map.of("counts", auditCounts(blindCount)));
    }

    /** OPEN → CLOSED by a manager (P22); settlement has checked the closer's permission and the attempts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeSessionForced(UUID sessionId, long expectedVersion, List<MethodCount> blindCount,
                                   UUID closerEmployeeId, String reason) {
        requireCurrentActor(closerEmployeeId, ActorKind.EMPLOYEE);
        CashSession session = sessionForUpdate(sessionId);
        OptimisticLocking.requireVersion(CashSession.class, sessionId, session.getVersion(), expectedVersion);
        List<CashSessionCount> recorded = session.closeForced(blindCount, closerEmployeeId, reason,
                clock.instant());
        counts.saveAll(recorded);
        sessions.saveAndFlush(session);
        audit.record("CASH_SESSION_FORCE_CLOSED", CashSessionService.ENTITY, sessionId, session.getStoreId(), null,
                session.getCloseReason(), Map.of("operatorEmployeeId", session.getOperatorEmployeeId().toString(),
                        "counts", auditCounts(blindCount)));
    }

    /** Codes are values, never keys: the audit guard reads every key as a field name (D82). */
    private static List<Map<String, String>> auditCounts(List<MethodCount> blindCount) {
        return blindCount.stream().map(c -> Map.of("methodCode", c.methodCode(),
                "amount", c.amount().amount().toPlainString())).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DayView lockDay(UUID storeId, LocalDate businessDate) {
        CashDay day = days.findByStoreIdAndBusinessDate(storeId, businessDate)
                .orElseThrow(() -> new CashNotFoundException(CashDayService.ENTITY, storeId + "/" + businessDate));
        return locks.lock(day, LockModeType.PESSIMISTIC_WRITE).toView();
    }

    @Transactional(readOnly = true)
    public UUID storeOfDay(UUID dayId) {
        return CashDayService.storeOf(stores, dayId);
    }

    @Transactional(readOnly = true)
    public UUID storeOfSession(UUID sessionId) {
        return stores.session(sessionId).orElseThrow(() -> new CashNotFoundException(CashSessionService.ENTITY,
                sessionId));
    }

    @Transactional(readOnly = true)
    public Optional<DayView> findDay(UUID dayId) {
        return days.findById(dayId).map(CashDay::toView);
    }

    @Transactional(readOnly = true)
    public Optional<DayView> findDay(UUID storeId, LocalDate businessDate) {
        return days.findByStoreIdAndBusinessDate(storeId, businessDate).map(CashDay::toView);
    }

    @Transactional(readOnly = true)
    public List<UUID> sessionIdsOfDays(UUID storeId, LocalDate from, LocalDate to) {
        return sessions.findIdsOfStoreBetween(storeId, from, to);
    }

    @Transactional(readOnly = true)
    public SessionDay sessionDay(UUID sessionId) {
        CashSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new CashNotFoundException(CashSessionService.ENTITY, sessionId));
        CashDay day = days.findById(session.getCashDayId()).orElseThrow();
        return new SessionDay(day.getId(), day.getBusinessDate(), day.getStatus().name());
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
