package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CancelAttemptRequest;
import com.xavier.backend.settlement.model.dto.ConfirmAttemptRequest;
import com.xavier.backend.settlement.repository.AttemptRef;
import com.xavier.backend.settlement.model.dto.CreateAttemptRequest;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, reading, confirming and cancelling payment attempts (spec §7.3–§7.5, D48–D50, P12, P16). Each command
 * authorizes on the order's or attempt's store alone (D42 (b)), then takes the order's settlement lock before
 * anything else (D51: order_settlement → cash_session), so two terminals paying one bill are serialized and the
 * second sees the first one's payment.
 */
@Service
public class PaymentAttemptService {

    static final String ORDER = "Order";

    private final AccessPolicy accessPolicy;
    private final SettlementLocks locks;
    private final Bills bills;
    private final PaymentMethodRepository methods;
    private final PaymentAttemptRepository attempts;
    private final PaymentRecorder recorder;
    private final OrdersApi orders;
    private final CashApi cash;
    private final SettlementAudit audit;
    private final PaymentLocks paymentLocks;
    private final PaymentProviders providers;
    private final PaymentSubmitter submitter;
    private final Clock clock;

    PaymentAttemptService(AccessPolicy accessPolicy, SettlementLocks locks, Bills bills,
                          PaymentMethodRepository methods, PaymentAttemptRepository attempts,
                          PaymentRecorder recorder, OrdersApi orders, CashApi cash, SettlementAudit audit,
                          PaymentLocks paymentLocks, PaymentProviders providers, PaymentSubmitter submitter,
                          Clock clock) {
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.bills = bills;
        this.methods = methods;
        this.attempts = attempts;
        this.recorder = recorder;
        this.orders = orders;
        this.cash = cash;
        this.audit = audit;
        this.paymentLocks = paymentLocks;
        this.providers = providers;
        this.submitter = submitter;
        this.clock = clock;
    }

    /**
     * D42 for a create, before its transaction (like D40's phase 1): the order's store is read (404) and checked
     * against the POS session (403) before anything is locked. The order's store never changes, so the create may
     * rely on it; it checks again, the same way, before its lock.
     */
    @Transactional(readOnly = true)
    public void authorizeCreate(CreateAttemptRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        accessPolicy.requireSessionStore(pos, orders.storeOf(request.orderId()), ORDER, request.orderId());
    }

    /**
     * CASH → SUCCEEDED with its Payment and receipt in this transaction; EXTERNAL_TERMINAL → PENDING; INTEGRATED →
     * CREATED. The attempt goes to the paying employee's OPEN session (P16). Every attempt locks the order for payment
     * (D49; a no-op once locked).
     *
     * @throws SettlementRuleException 422 {@code ORDER_NOT_OPEN}, {@code UNKNOWN_PAYMENT_METHOD},
     *         {@code INVALID_AMOUNT}, {@code AMOUNT_EXCEEDS_BALANCE}, {@code NO_OPEN_CASH_SESSION}, ...
     * @throws SettlementConflictException 409 {@code PAYMENT_IN_DOUBT} while another attempt of the order is open
     */
    @Transactional
    public AttemptView create(CreateAttemptRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        accessPolicy.requireSessionStore(pos, orders.storeOf(request.orderId()), ORDER, request.orderId());
        locks.lockOrder(request.orderId());
        Bill bill = bills.of(request.orderId());
        bill.requireOpen();
        PaymentMethod method = activeMethod(request.methodCode());
        if (method.getKind() == PaymentMethodKind.INTEGRATED && !providers.exists(method.getProviderCode())) {
            throw new SettlementRuleException(SettlementRule.PROVIDER_NOT_AVAILABLE,
                    "The provider " + method.getProviderCode() + " of method " + method.getCode()
                            + " is not installed.");
        }
        requireNoOpenAttempt(request.orderId());
        Currency currency = bill.due().currency();
        Money amount = money(request.amount(), currency);
        if (amount.amount().compareTo(bill.balance().amount()) > 0) {
            throw new SettlementRuleException(SettlementRule.AMOUNT_EXCEEDS_BALANCE, "The amount " + amount.amount()
                    + " is more than the balance " + bill.balance().amount() + " of the order.",
                    Map.of("balance", bill.balance().amount()));
        }
        SessionView session = openSessionOf(pos);
        orders.lockForPayment(request.orderId());

        PaymentAttempt attempt = PaymentAttempt.create(request.orderId(), pos.storeId(), pos.terminalId(),
                session.id(), method, amount, request.tendered() == null ? null : money(request.tendered(), currency),
                pos.employeeId(), clock.instant());
        UniqueConstraints.saveAndFlush(attempts, attempt,
                () -> SettlementConflictException.inDoubt("order", request.orderId(), attempt.getId()),
                "payment_attempt_open_per_target_uk");
        audit.attempt("PAYMENT_ATTEMPT_CREATED", attempt.getId(), attempt.getStoreId(), attempt.getOrderId(),
                attempt.getTerminalId(), null, null, details(attempt));
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED) {
            recorder.record(attempt);
        }
        if (attempt.getStatus() == AttemptStatus.CREATED) {
            submitAfterCommit(attempt.getId());
        }
        return attempt.toView();
    }

    /** D48: the provider is called only once the CREATED attempt is committed, by the submitter, never here. */
    private void submitAfterCommit(UUID attemptId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitter.submit(attemptId);
            }
        });
    }

    @Transactional(readOnly = true)
    public AttemptView get(UUID attemptId) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        authorized(pos, attemptId);
        return attempts.findById(attemptId).orElseThrow().toView();
    }

    /**
     * EXTERNAL_TERMINAL PENDING → SUCCEEDED (D50 §7.5): the Payment, its allocation and the receipt in this
     * transaction, under the order's lock. A second confirmation finds it SUCCEEDED: 409, nothing paid twice.
     */
    @Transactional
    public AttemptView confirm(UUID attemptId, ConfirmAttemptRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        PaymentAttempt attempt = lockedAttempt(pos, attemptId);
        attempt.confirm(request.authorizationCode(), clock.instant());
        attempts.saveAndFlush(attempt);
        recorder.record(attempt);
        return attempt.toView();
    }

    /**
     * CREATED or PENDING (an integrated one only before its provider is called) → CANCELLED. When nothing of the
     * order is open or paid any more, its payment lock is released (D49).
     */
    @Transactional
    public AttemptView cancel(UUID attemptId, CancelAttemptRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        PaymentAttempt attempt = lockedAttempt(pos, attemptId);
        attempt.cancel(request.reason(), pos.employeeId(), clock.instant());
        attempts.saveAndFlush(attempt);
        audit.attempt("PAYMENT_ATTEMPT_CANCELLED", attempt.getId(), attempt.getStoreId(), attempt.getOrderId(),
                attempt.getTerminalId(), null, request.reason(), details(attempt));
        paymentLocks.releaseIfIdle(attempt.getOrderId());
        return attempt.toView();
    }

    /** D42 then D51: the attempt's store (404, 403), the order's settlement lock, then the attempt as committed. */
    PaymentAttempt lockedAttempt(PosContext pos, UUID attemptId) {
        AttemptRef ref = authorized(pos, attemptId);
        locks.lockOrder(ref.orderId());
        return attempts.findById(attemptId).orElseThrow();
    }

    private AttemptRef authorized(PosContext pos, UUID attemptId) {
        AttemptRef ref = attempts.findRefById(attemptId)
                .orElseThrow(() -> new SettlementNotFoundException(SettlementAudit.ATTEMPT, attemptId));
        accessPolicy.requireSessionStore(pos, ref.storeId(), SettlementAudit.ATTEMPT, attemptId);
        return ref;
    }

    private PaymentMethod activeMethod(String code) {
        return methods.findByCode(code).filter(PaymentMethod::isActive)
                .orElseThrow(() -> new SettlementRuleException(SettlementRule.UNKNOWN_PAYMENT_METHOD,
                        "No active payment method has the code " + code + "."));
    }

    private void requireNoOpenAttempt(UUID orderId) {
        attempts.findByOrderIdAndStatusInOrderByCreatedAt(orderId, AttemptStatus.OPEN).stream().findFirst()
                .ifPresent(open -> {
                    throw SettlementConflictException.inDoubt("order", orderId, open.getId());
                });
    }

    /** The paying employee's OPEN session, locked against a concurrent close (D51: after order_settlement). */
    private SessionView openSessionOf(PosContext pos) {
        OpenSessionView open = cash.openSessionOf(pos.storeId(), pos.employeeId())
                .orElseThrow(SettlementRuleException::noOpenSession);
        SessionView locked = cash.lockSession(open.id());
        if (!"OPEN".equals(locked.status())) {
            throw SettlementRuleException.noOpenSession();
        }
        return locked;
    }

    static Money money(BigDecimal amount, Currency currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT,
                    "An amount in " + currency.getCurrencyCode() + " has at most "
                            + currency.getDefaultFractionDigits() + " decimals.");
        }
    }

    static Map<String, Object> details(PaymentAttempt attempt) {
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", attempt.getOrderId().toString());
        details.put("methodCode", attempt.getMethodCode());
        details.put("amount", attempt.amount().amount().toPlainString());
        details.put("status", attempt.getStatus().name());
        return details;
    }
}
