package com.xavier.backend.settlement.service;

import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.customers.CreditView;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CancelAttemptRequest;
import com.xavier.backend.settlement.model.dto.ConfirmAttemptRequest;
import com.xavier.backend.settlement.repository.AttemptRef;
import com.xavier.backend.settlement.model.dto.CreateAttemptRequest;
import com.xavier.backend.settlement.model.dto.CreditPaymentRequest;
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
    static final String CUSTOMER = "customer";

    private final StoreProbe storeProbe;
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
    private final CustomersApi customers;
    private final Clock clock;

    PaymentAttemptService(StoreProbe storeProbe, AccessPolicy accessPolicy, SettlementLocks locks, Bills bills,
                          PaymentMethodRepository methods, PaymentAttemptRepository attempts,
                          PaymentRecorder recorder, OrdersApi orders, CashApi cash, SettlementAudit audit,
                          PaymentLocks paymentLocks, PaymentProviders providers, PaymentSubmitter submitter,
                          CustomersApi customers, Clock clock) {
        this.storeProbe = storeProbe;
        this.customers = customers;
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
        PaymentMethod method = payableMethod(request.methodCode());
        Currency currency = bill.due().currency();
        Money balance = targetBalance(bill, request.partitionId());
        requireNoOpenAttempt(bill, request.partitionId());
        Money amount = money(request.amount(), currency);
        if (amount.amount().compareTo(balance.amount()) > 0) {
            throw new SettlementRuleException(SettlementRule.AMOUNT_EXCEEDS_BALANCE, "The amount " + amount.amount()
                    + " is more than the balance " + balance.amount() + " of the "
                    + (request.partitionId() == null ? "order" : "partition") + ".",
                    Map.of("balance", balance.amount()));
        }
        SessionView session = openSessionOf(pos);
        orders.lockForPayment(request.orderId());

        PaymentAttempt attempt = PaymentAttempt.create(request.orderId(), request.partitionId(), pos.storeId(),
                pos.terminalId(),
                session.id(), method, amount, request.tendered() == null ? null : money(request.tendered(), currency),
                pos.employeeId(), clock.instant());
        UniqueConstraints.saveAndFlush(attempts, attempt,
                () -> SettlementConflictException.inDoubt(targetName(request.partitionId()),
                        target(request), attempt.getId()),
                "payment_attempt_open_per_target_uk");
        return started(attempt);
    }

    /**
     * A debt payment (D75) of the customer's open debt, by the R2 flow of its method: CASH → SUCCEEDED with its
     * Payment, receipt, CREDIT and slip in this transaction; EXTERNAL_TERMINAL → PENDING; INTEGRATED → CREATED. It
     * starts at the customer's {@code credit_account} lock (D76), so two debt payments of one customer are
     * serialized and the debt is read as committed. The customer's account is company-wide (P38): any store collects.
     *
     * @throws SettlementRuleException 422 {@code AMOUNT_EXCEEDS_DEBT} (P33: no advance), {@code INVALID_AMOUNT},
     *         {@code UNKNOWN_PAYMENT_METHOD}, {@code NO_OPEN_CASH_SESSION}, ...; customers' 422
     *         {@code CREDIT_ACCOUNT_MISSING}
     * @throws SettlementConflictException 409 {@code PAYMENT_IN_DOUBT} while another debt payment of the customer is
     *         open
     */
    @Transactional
    public AttemptView createForCustomer(UUID customerId, CreditPaymentRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        CreditView account = customers.lockAccount(customerId);
        PaymentMethod method = payableMethod(request.methodCode());
        attempts.findFirstByCreditAccountIdAndStatusIn(customerId, AttemptStatus.OPEN).ifPresent(open -> {
            throw SettlementConflictException.inDoubt(CUSTOMER, customerId, open.getId());
        });
        Currency currency = account.balance().currency();
        Money amount = money(request.amount(), currency);
        if (amount.amount().compareTo(account.balance().amount()) > 0) {
            throw SettlementRuleException.amountExceedsDebt(amount, account.balance());
        }
        requireNamedDebitsCover(customerId, request.debitEntryIds(), amount);
        SessionView session = openSessionOf(pos);
        PaymentAttempt attempt = PaymentAttempt.forCreditAccount(customerId, request.debitEntryIds(), pos.storeId(),
                pos.terminalId(), session.id(), method, amount,
                request.tendered() == null ? null : money(request.tendered(), currency), pos.employeeId(),
                clock.instant());
        UniqueConstraints.saveAndFlush(attempts, attempt,
                () -> SettlementConflictException.inDoubt(CUSTOMER, customerId, attempt.getId()),
                "payment_attempt_open_per_target_uk");
        return started(attempt);
    }

    /**
     * The debits a debt payment names are checked before any money is taken (D75): each must be an open debit of the
     * customer, and together they must cover the amount, as {@code postCredit} will require on success.
     */
    private void requireNamedDebitsCover(UUID customerId, java.util.List<UUID> named, Money amount) {
        if (named == null || named.isEmpty()) {
            return;
        }
        Map<UUID, Money> open = customers.openDebits(customerId, named);
        Money covered = Money.of(BigDecimal.ZERO, amount.currency());
        for (UUID debit : new java.util.LinkedHashSet<>(named)) {
            Money left = open.get(debit);
            if (left == null || left.amount().signum() <= 0) {
                throw SettlementRuleException.debitNotOpen(debit);
            }
            covered = covered.plus(left);
        }
        if (amount.amount().compareTo(covered.amount()) > 0) {
            throw SettlementRuleException.amountExceedsDebt(amount, covered);
        }
    }

    /** Audits the new attempt, then pays it (cash) or submits it to its provider after commit (integrated). */
    private AttemptView started(PaymentAttempt attempt) {
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

    /**
     * D42 then D51/D76: the attempt's store (404, 403), the lock of its target (the order's settlement lock, or a debt
     * payment's credit account), then the attempt as committed.
     */
    PaymentAttempt lockedAttempt(PosContext pos, UUID attemptId) {
        AttemptRef ref = authorized(pos, attemptId);
        locks.lockTargetOf(ref);
        return attempts.findById(attemptId).orElseThrow();
    }

    /** D42: the attempt's store across every store (D168), 404 then 403, before its ref is read. */
    private AttemptRef authorized(PosContext pos, UUID attemptId) {
        UUID storeId = storeProbe.storeOf("settlement.payment_attempt", attemptId)
                .orElseThrow(() -> new SettlementNotFoundException(SettlementAudit.ATTEMPT, attemptId));
        accessPolicy.requireSessionStore(pos, storeId, SettlementAudit.ATTEMPT, attemptId);
        return attempts.findRefById(attemptId).orElseThrow();
    }

    /** An active method whose provider (for an integrated one) is installed (D53). */
    private PaymentMethod payableMethod(String code) {
        PaymentMethod method = methods.findByCode(code).filter(PaymentMethod::isActive)
                .orElseThrow(() -> new SettlementRuleException(SettlementRule.UNKNOWN_PAYMENT_METHOD,
                        "No active payment method has the code " + code + "."));
        if (method.getKind() == PaymentMethodKind.INTEGRATED && !providers.exists(method.getProviderCode())) {
            throw new SettlementRuleException(SettlementRule.PROVIDER_NOT_AVAILABLE,
                    "The provider " + method.getProviderCode() + " of method " + method.getCode()
                            + " is not installed.");
        }
        return method;
    }

    /**
     * D69: the balance of the target: a LIVE partition of the order (404 for another order's, 409 once cancelled), or
     * the order's residual.
     */
    private static Money targetBalance(Bill bill, UUID partitionId) {
        if (partitionId == null) {
            return bill.residualBalance();
        }
        BillPartition partition = bill.partition(partitionId)
                .orElseThrow(() -> new SettlementNotFoundException(PartitionService.PARTITION, partitionId));
        if (!partition.isLive()) {
            throw SettlementConflictException.illegalPartitionState(partitionId, partition.getStatus(), "be paid");
        }
        return bill.balanceOf(partition);
    }

    /** D69: one open attempt per target; an attempt in doubt on another target does not block this one. */
    private static void requireNoOpenAttempt(Bill bill, UUID partitionId) {
        bill.openOn(partitionId).stream().findFirst().ifPresent(open -> {
            throw SettlementConflictException.inDoubt(targetName(partitionId),
                    partitionId == null ? bill.view().orderId() : partitionId, open.getId());
        });
    }

    private static String targetName(UUID partitionId) {
        return partitionId == null ? "order" : "partition";
    }

    private static UUID target(CreateAttemptRequest request) {
        return request.partitionId() == null ? request.orderId() : request.partitionId();
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
        if (attempt.getOrderId() != null) {
            details.put("orderId", attempt.getOrderId().toString());
        }
        if (attempt.getCreditAccountId() != null) {
            details.put("customerId", attempt.getCreditAccountId().toString());
        }
        details.put("methodCode", attempt.getMethodCode());
        details.put("amount", attempt.amount().amount().toPlainString());
        details.put("status", attempt.getStatus().name());
        return details;
    }
}
