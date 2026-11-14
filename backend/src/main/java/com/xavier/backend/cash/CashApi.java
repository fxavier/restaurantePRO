package com.xavier.backend.cash;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.service.CashCoordinationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What settlement asks the cash module (D36, D37; PRD S11). The write methods are {@code MANDATORY}: settlement
 * composes them in its own transaction (payment + receipt, the session close, the day's operational close). Nothing
 * here authorizes; the calling use case already did (D27). Lock order (D51): order_settlement → cash_session.
 *
 * <p>Errors: an unknown session or day → {@link com.xavier.backend.shared.exception.NotFoundException} (404); an
 * illegal transition → {@link com.xavier.backend.shared.exception.ConflictException} (409); a stale
 * {@code expectedVersion} → {@link org.springframework.dao.OptimisticLockingFailureException} (409); a cash rule →
 * {@link com.xavier.backend.cash.exception.CashRuleViolationException} (a {@code BusinessRuleException}) 422 with a
 * stable {@code code} property
 * ({@code SESSION_NOT_OPEN}, {@code OPEN_SESSIONS}, {@code DUPLICATE_METHOD}, {@code INVALID_AMOUNT},
 * {@code CURRENCY_MISMATCH}).
 */
@Service
public class CashApi {

    private final CashCoordinationService coordination;

    CashApi(CashCoordinationService coordination) {
        this.coordination = coordination;
    }

    /** The OPEN session a payment collected by {@code employeeId} in {@code storeId} goes to (P16). */
    @Transactional(readOnly = true)
    public Optional<OpenSessionView> openSessionOf(UUID storeId, UUID employeeId) {
        return coordination.openSessionOf(storeId, employeeId);
    }

    /**
     * Records a confirmed Payment as a {@code PAYMENT_RECEIPT} movement of its method in its session. Idempotent:
     * the same {@code paymentId} returns the movement already recorded and adds nothing. The session must be OPEN
     * (422 {@code SESSION_NOT_OPEN}), and the amount positive and in the store currency.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView recordReceipt(ReceiptCommand cmd) {
        return coordination.recordReceipt(cmd);
    }

    /**
     * Records a confirmed Refund as a {@code REFUND} OUT movement of its method in the refunder's session (A23).
     * Idempotent per {@code refundId}: a repeat returns the movement already recorded, and one with another session,
     * method or amount is 409 {@code REFUND_MISMATCH}. The session must be OPEN
     * (422 {@code SESSION_NOT_OPEN}), and the amount positive and in the store currency.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView recordRefund(RefundCommand cmd) {
        return coordination.recordRefund(cmd);
    }

    /** Locks the session {@code FOR UPDATE} until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockSession(UUID sessionId) {
        return coordination.lockSession(sessionId);
    }

    /**
     * OPEN → CLOSED, recording the blind count per method (immutable). A method not counted counts as zero in the
     * closing. Settlement checks first that no payment attempt of the session is in doubt (spec §8.4).
     *
     * <p>Audited as the current actor (D41), so it needs an authenticated session, and {@code actorEmployeeId} must
     * be that actor, an EMPLOYEE ({@link IllegalArgumentException} otherwise).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeSession(UUID sessionId, long expectedVersion, List<MethodCount> counts, UUID actorEmployeeId) {
        coordination.closeSession(sessionId, expectedVersion, counts, actorEmployeeId);
    }

    /**
     * OPEN → CLOSED by a manager, for a session its operator abandoned (P22): the blind count as in
     * {@link #closeSession}, the closer and the reason are recorded, and the session's {@code close_kind} is
     * {@code FORCED}. Settlement checks first that no payment attempt of the session is in doubt (spec §8.4) and that
     * the closer holds {@code CASH_SESSION_FORCE_CLOSE} in the session's store.
     *
     * <p>Audited as the current actor (D41) with the reason; {@code closerEmployeeId} must be that actor, an
     * EMPLOYEE, and {@code reason} must not be blank ({@link IllegalArgumentException} otherwise).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeSessionForced(UUID sessionId, long expectedVersion, List<MethodCount> counts,
                                   UUID closerEmployeeId, String reason) {
        coordination.closeSessionForced(sessionId, expectedVersion, counts, closerEmployeeId, reason);
    }

    /** Locks the store's cash day of {@code businessDate} {@code FOR UPDATE}; 404 if it was never opened. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DayView lockDay(UUID storeId, LocalDate businessDate) {
        return coordination.lockDay(storeId, businessDate);
    }

    /**
     * Only the cash day's store, for D42 (b): read it, authorize against it, and only then lock or load the day; 404
     * if the day does not exist. A day's store never changes.
     */
    @Transactional(readOnly = true)
    public UUID storeOfDay(UUID dayId) {
        return coordination.storeOfDay(dayId);
    }

    /**
     * Only the session's store, for D42 (b), read across every store (D168): another store's session is found (then
     * 403), never mistaken for absent; 404 if the session does not exist.
     */
    @Transactional(readOnly = true)
    public UUID storeOfSession(UUID sessionId) {
        return coordination.storeOfSession(sessionId);
    }

    /** The cash day of the session (D85); 404 if the session does not exist. */
    @Transactional(readOnly = true)
    public SessionDay sessionDay(UUID sessionId) {
        return coordination.sessionDay(sessionId);
    }

    /**
     * The ids of the store's sessions whose cash day's business date is in {@code [from, to]} (both inclusive), for
     * settlement's reporting facts: payments recorded before R3 carry no business date and take their session's.
     */
    @Transactional(readOnly = true)
    public List<UUID> sessionIdsOfDays(UUID storeId, LocalDate from, LocalDate to) {
        return coordination.sessionIdsOfDays(storeId, from, to);
    }

    /** The store's cash day of {@code businessDate}, without a lock; empty if it was never opened (D86 finality). */
    @Transactional(readOnly = true)
    public Optional<DayView> findDay(UUID storeId, LocalDate businessDate) {
        return coordination.findDay(storeId, businessDate);
    }

    /** The cash day, without a lock. */
    @Transactional(readOnly = true)
    public Optional<DayView> findDay(UUID dayId) {
        return coordination.findDay(dayId);
    }

    /**
     * OPEN → OPERATIONALLY_CLOSED. Cash refuses it while a session of the day is OPEN (422 {@code OPEN_SESSIONS});
     * settlement checks orders, payment attempts and fiscal drafts (D50 §7.8).
     *
     * <p>Audited as the current actor (D41), so it needs an authenticated session, and {@code actorId}/{@code kind}
     * must be that actor's principal id and kind ({@link IllegalArgumentException} otherwise).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeDayOperationally(UUID dayId, long expectedVersion, UUID actorId, ActorKind kind) {
        coordination.closeDayOperationally(dayId, expectedVersion, actorId, kind);
    }

    /** The ids of the day's OPEN sessions, in id order. */
    @Transactional(readOnly = true)
    public List<UUID> openSessionIds(UUID dayId) {
        return coordination.openSessionIds(dayId);
    }
}
