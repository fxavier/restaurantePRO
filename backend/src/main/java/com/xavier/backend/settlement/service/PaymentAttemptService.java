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
import com.xavier.backend.settlement.model.dto.AttemptView;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, reading, confirming and cancelling payment attempts (spec §7.3–§7.5, D48–D50, P12, P16). Each command
 * takes the order's settlement lock first (D51: order_settlement → cash_session), so two terminals paying one bill
 * are serialized and the second sees the first one's payment.
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
    private final Clock clock;

    PaymentAttemptService(AccessPolicy accessPolicy, SettlementLocks locks, Bills bills,
                          PaymentMethodRepository methods, PaymentAttemptRepository attempts,
                          PaymentRecorder recorder, OrdersApi orders, CashApi cash, SettlementAudit audit,
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
        this.clock = clock;
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
        locks.lockOrder(request.orderId());
        Bill bill = bills.of(request.orderId());
        accessPolicy.requireSessionStore(pos, bill.storeId(), ORDER, request.orderId());
        bill.requireOpen();
        PaymentMethod method = activeMethod(request.methodCode());
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
                "payment_attempt_open_per_order_uk");
        audit.attempt("PAYMENT_ATTEMPT_CREATED", attempt.getId(), attempt.getStoreId(), attempt.getOrderId(),
                attempt.getTerminalId(), null, null, details(attempt));
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED) {
            recorder.record(attempt);
        }
        return attempt.toView();
    }

    @Transactional(readOnly = true)
    public AttemptView get(UUID attemptId) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        UUID storeId = attempts.findStoreIdById(attemptId)
                .orElseThrow(() -> new SettlementNotFoundException(SettlementAudit.ATTEMPT, attemptId));
        accessPolicy.requireSessionStore(pos, storeId, SettlementAudit.ATTEMPT, attemptId);
        return attempts.findById(attemptId).orElseThrow().toView();
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
