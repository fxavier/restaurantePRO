package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.ChargeRequest;
import com.xavier.backend.settlement.ProviderOutcome;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.repository.AttemptRef;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions around an integrated provider call (D48), run by the submitter, the sweeper and the resolve
 * with no session (audited as the system) or in one (as the actor). Each decides on the attempt as committed, under
 * the order's settlement lock where money may move.
 */
@Component
class AttemptTransitions {

    private final PaymentAttemptRepository attempts;
    private final SettlementLocks locks;
    private final PaymentRecorder recorder;
    private final PaymentLocks paymentLocks;
    private final SettlementAudit audit;
    private final SettlementProperties properties;
    private final Clock clock;

    AttemptTransitions(PaymentAttemptRepository attempts, SettlementLocks locks, PaymentRecorder recorder,
                       PaymentLocks paymentLocks, SettlementAudit audit, SettlementProperties properties,
                       Clock clock) {
        this.attempts = attempts;
        this.locks = locks;
        this.recorder = recorder;
        this.paymentLocks = paymentLocks;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * tx-a: CREATED → PENDING by compare-and-set on the attempt's version, so of two submitters exactly one calls the
     * provider. @return the charge to send, or empty when the attempt is not CREATED any more (or another won)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Submission> submit(UUID attemptId) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != AttemptStatus.CREATED) {
            return Optional.empty();
        }
        attempt.submit(clock.instant());
        try {
            attempts.saveAndFlush(attempt);
        } catch (OptimisticLockingFailureException lostTheRace) {
            return Optional.empty();
        }
        audit.attempt("PAYMENT_ATTEMPT_SUBMITTED", attempt.getId(), attempt.getStoreId(), attempt.getOrderId(),
                attempt.getTerminalId(), null, null, PaymentAttemptService.details(attempt));
        return Optional.of(new Submission(attempt.getProviderCode(), new ChargeRequest(attempt.getProviderReference(),
                attempt.amount(), attempt.getStoreId(), attempt.getOrderId())));
    }

    /** tx-b: the provider's answer to the charge, recorded only on a PENDING attempt (nobody resolved it yet). */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordCharge(UUID attemptId, ProviderOutcome outcome) {
        PaymentAttempt attempt = locked(attemptId);
        if (attempt.getStatus() != AttemptStatus.PENDING) {
            return;
        }
        Instant now = clock.instant();
        switch (outcome) {
            case ProviderOutcome.Succeeded succeeded -> attempt.recordProviderSuccess(succeeded.providerRef(), now);
            case ProviderOutcome.Failed failed -> attempt.recordProviderFailure(failed.reason(), now);
            case ProviderOutcome.Unknown unknown -> attempt.markUnknown(unknown.detail(), now);
            case ProviderOutcome.Pending pending -> {
                return; // the provider decides later: the sweeper makes it UNKNOWN past the timeout
            }
        }
        settle(attempt, "PAYMENT_ATTEMPT_" + attempt.getStatus().name());
    }

    /** Sweeper: a PENDING integrated attempt whose caller vanished (a crash during the call) → UNKNOWN. */
    @Transactional(propagation = Propagation.MANDATORY)
    void expire(UUID attemptId, String detail) {
        PaymentAttempt attempt = locked(attemptId);
        if (attempt.getStatus() != AttemptStatus.PENDING) {
            return;
        }
        attempt.markUnknown(detail, clock.instant());
        settle(attempt, "PAYMENT_ATTEMPT_UNKNOWN");
    }

    /**
     * Resolve, step s3 (D63, spec §7.4, D50 §7.4): the provider's answer to a status query, under the order's lock.
     *
     * <ul>
     *   <li>{@code Succeeded} settles an UNKNOWN or PENDING attempt: its Payment and receipt, exactly once.</li>
     *   <li>{@code Failed} settles only an UNKNOWN attempt whose last transition is older than the provider timeout
     *       plus the pending margin, so no charge for it can still be in flight. For a PENDING attempt, or a recent
     *       UNKNOWN one, a provider that does not know the reference yet would wrongly fail a charge that then
     *       succeeds; the attempt stays as it is (the sweeper makes a PENDING one UNKNOWN later).</li>
     *   <li>{@code Pending} and {@code Unknown} change nothing.</li>
     * </ul>
     *
     * @return the attempt as it now is
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PaymentAttempt resolve(UUID attemptId, ProviderOutcome outcome, UUID employeeId) {
        PaymentAttempt attempt = locked(attemptId);
        if (attempt.getStatus() != AttemptStatus.UNKNOWN && attempt.getStatus() != AttemptStatus.PENDING) {
            return attempt;
        }
        Instant now = clock.instant();
        if (outcome instanceof ProviderOutcome.Succeeded succeeded) {
            attempt.resolveFromProvider(true, succeeded.providerRef(), null, employeeId, now);
        } else if (outcome instanceof ProviderOutcome.Failed failed && noChargeInFlight(attempt, now)) {
            attempt.resolveFromProvider(false, null, failed.reason(), employeeId, now);
        } else {
            return attempt;
        }
        settle(attempt, "PAYMENT_ATTEMPT_RESOLVED");
        return attempt;
    }

    private boolean noChargeInFlight(PaymentAttempt attempt, Instant now) {
        Duration inFlight = properties.providerTimeout().plus(properties.pendingMargin());
        return attempt.getStatus() == AttemptStatus.UNKNOWN && attempt.getUpdatedAt().plus(inFlight).isBefore(now);
    }

    /** D51: the order's lock first, then the attempt as committed. */
    PaymentAttempt locked(UUID attemptId) {
        AttemptRef ref = attempts.findRefById(attemptId).orElseThrow();
        locks.lockOrder(ref.orderId());
        return attempts.findById(attemptId).orElseThrow();
    }

    /** Saves the transition, audits it, and pays a SUCCEEDED attempt or releases the lock after a FAILED one. */
    void settle(PaymentAttempt attempt, String action) {
        attempts.saveAndFlush(attempt);
        audit.attempt(action, attempt.getId(), attempt.getStoreId(), attempt.getOrderId(), attempt.getTerminalId(),
                null, null, PaymentAttemptService.details(attempt));
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED) {
            recorder.record(attempt);
        } else if (attempt.getStatus() == AttemptStatus.FAILED) {
            paymentLocks.releaseIfIdle(attempt.getOrderId());
        }
    }

    /** What the submitter sends to which provider. */
    record Submission(String providerCode, ChargeRequest charge) {
    }
}
