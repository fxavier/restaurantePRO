package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.ResolveAttemptRequest;
import com.xavier.backend.settlement.repository.AttemptRef;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolving a payment attempt in doubt (spec §7.4, D50 §7.4), with {@code PAYMENT_RESOLVE}.
 *
 * <p>By provider query, a D63 multi-step command with no transaction around it: (s1) a short transaction reads the
 * attempt (a final one is returned as it is); (s2) the provider's {@code status} with no transaction; (s3) a short
 * transaction takes the order's lock, re-reads the attempt and records the answer (a SUCCEEDED attempt becomes its
 * Payment and receipt exactly once: the status compare, {@code UNIQUE attempt_id} and {@code UNIQUE source}). A
 * {@code Failed} answer is final only for an UNKNOWN attempt with no charge still in flight
 * ({@link AttemptTransitions#resolve}); a PENDING attempt stays PENDING.
 *
 * <p>By manual reclassification, D40's two phases: {@link #authorizeReclassification} verifies the approver before
 * any transaction; {@link #reclassify} applies it in one transaction.
 */
@Service
public class AttemptResolutionService {

    private final AccessPolicy accessPolicy;
    private final PaymentAttemptRepository attempts;
    private final ProviderStatusQuery statusQuery;
    private final AttemptTransitions transitions;
    private final ApproverVerifier approvers;
    private final PaymentRecorder recorder;
    private final PaymentLocks paymentLocks;
    private final SettlementAudit audit;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;
    private final SettlementProperties properties;
    private final Clock clock;

    AttemptResolutionService(AccessPolicy accessPolicy, PaymentAttemptRepository attempts,
                             ProviderStatusQuery statusQuery, AttemptTransitions transitions,
                             ApproverVerifier approvers, PaymentRecorder recorder, PaymentLocks paymentLocks,
                             SettlementAudit audit, SettlementProperties properties,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.attempts = attempts;
        this.statusQuery = statusQuery;
        this.transitions = transitions;
        this.approvers = approvers;
        this.recorder = recorder;
        this.paymentLocks = paymentLocks;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The D63 steps; call with no transaction (from {@code executeSteps}). Each step is idempotent by the attempt's
     * state, so a retry after a failure at any point ends in the same state.
     *
     * @throws SettlementConflictException 409 {@code ILLEGAL_ATTEMPT_STATE} for an attempt that is not integrated
     */
    public AttemptView resolveByProvider(UUID attemptId) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_RESOLVE);
        PaymentAttempt read = readOnly.execute(tx -> {
            authorized(pos, attemptId);
            return attempts.findById(attemptId).orElseThrow();
        });
        if (!read.getStatus().isOpen()) {
            return read.toView();
        }
        if (read.getMethodKind() != PaymentMethodKind.INTEGRATED || read.getStatus() == AttemptStatus.CREATED) {
            throw SettlementConflictException.illegalState(attemptId, read.getStatus(), "be resolved by a provider "
                    + "query (it is " + read.getMethodKind() + ")");
        }
        ProviderStatusQuery.Answer answer = statusQuery.ask(read);
        return inTransaction.execute(tx -> transitions.resolve(attemptId, answer, pos.employeeId()).toView());
    }

    /**
     * D40 phase 1, before any transaction: the approver holds {@code PAYMENT_RESOLVE} in the attempt's store and is
     * not the operator.
     *
     * @throws ApprovalDeniedException (403) for missing or refused approver credentials
     * @throws SettlementRuleException 422 {@code SELF_APPROVAL}
     */
    public Approval authorizeReclassification(UUID attemptId, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_RESOLVE);
        AttemptRef ref = authorized(pos, attemptId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        UUID approver = approvers.verify(approverCode, approverPin, Permission.PAYMENT_RESOLVE, ref.storeId());
        if (approver.equals(pos.employeeId())) {
            throw new SettlementRuleException(SettlementRule.SELF_APPROVAL,
                    "The approver of a reclassification cannot be the operator.");
        }
        return new Approval(attemptId, approver);
    }

    /**
     * D40 phase 2: UNKNOWN → SUCCEEDED (Payment and receipt) or FAILED, with the approver and the reason.
     *
     * @throws SettlementRuleException 422 {@code RECLASSIFY_TOO_EARLY}: to FAILED before
     *         {@code restaurantpro.settlement.reclassify-failed-min-age} or before any provider query (D81 (3))
     */
    @Transactional
    public AttemptView reclassify(UUID attemptId, ResolveAttemptRequest request, Approval approval) {
        if (!approval.attemptId().equals(attemptId)) {
            throw new IllegalArgumentException("The approval is for attempt " + approval.attemptId());
        }
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_RESOLVE);
        authorized(pos, attemptId);
        PaymentAttempt attempt = transitions.locked(attemptId);
        attempt.reclassify(request.outcome(), request.reason(), pos.employeeId(), approval.approverEmployeeId(),
                clock.instant(), properties.reclassifyFailedMinAge());
        attempts.saveAndFlush(attempt);
        audit.attempt("PAYMENT_ATTEMPT_RECLASSIFIED", attempt.getId(), attempt.getStoreId(), attempt.getOrderId(),
                attempt.getTerminalId(), approval.approverEmployeeId(), request.reason(),
                PaymentAttemptService.details(attempt));
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED) {
            recorder.record(attempt);
        } else {
            paymentLocks.releaseIfIdle(attempt.getOrderId());
        }
        return attempt.toView();
    }

    private AttemptRef authorized(PosContext pos, UUID attemptId) {
        AttemptRef ref = attempts.findRefById(attemptId)
                .orElseThrow(() -> new SettlementNotFoundException(SettlementAudit.ATTEMPT, attemptId));
        accessPolicy.requireSessionStore(pos, ref.storeId(), SettlementAudit.ATTEMPT, attemptId);
        return ref;
    }
}
