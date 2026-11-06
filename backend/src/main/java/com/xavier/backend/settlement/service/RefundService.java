package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.RefundCommand;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.Refund;
import com.xavier.backend.settlement.model.TargetType;
import com.xavier.backend.settlement.model.dto.RefundRequest;
import com.xavier.backend.settlement.model.dto.RefundView;
import com.xavier.backend.settlement.model.event.RefundRecorded;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.repository.RefundRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds on the POS (spec §8.3 "Devolução é Refund com referência ao pagamento", D77, P30, P31), with
 * {@code PAYMENT_REFUND} and an approver holding {@code PAYMENT_REFUND_APPROVE} (D40's two phases).
 *
 * <p>Phase 2 is one transaction in the D76 lock order: the payment's target lock ({@code order_settlement}, or the
 * customer's {@code credit_account} for a debt payment) → the refundable checks on what is committed → the
 * {@link Refund} → the refunder's {@code cash_session}: a {@code REFUND} OUT movement of the payment's own method
 * (cash, or a terminal's refund whose reference the operator gives, so the closing compares terminal totals, D52)
 * and, for a debt payment, {@code CustomersApi.reverseCredit}, which reopens the debt. It publishes
 * {@link RefundRecorded}. R3 has no integrated refund API (P10): an integrated payment is refunded at the provider's
 * portal and recorded here like a terminal's.
 *
 * <p>What may be refunded: the payment less its earlier refunds; and, when an ISSUED document covers the payment's
 * target, only the target's overpayment ({@code paid + credit − due}): anything more needs a corrective document,
 * which comes with the market's fiscal adapter (P31).
 */
@Service
public class RefundService {

    static final String PAYMENT = "Payment";

    private final AccessPolicy accessPolicy;
    private final ApproverVerifier approvers;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final SettlementLocks locks;
    private final Bills bills;
    private final FiscalApi fiscal;
    private final CashApi cash;
    private final CustomersApi customers;
    private final SettlementAudit audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RefundService(AccessPolicy accessPolicy, ApproverVerifier approvers, PaymentRepository payments,
                  PaymentAttemptRepository attempts, RefundRepository refunds, SettlementLocks locks, Bills bills,
                  FiscalApi fiscal, CashApi cash, CustomersApi customers, SettlementAudit audit,
                  ApplicationEventPublisher events, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.approvers = approvers;
        this.payments = payments;
        this.attempts = attempts;
        this.refunds = refunds;
        this.locks = locks;
        this.bills = bills;
        this.fiscal = fiscal;
        this.cash = cash;
        this.customers = customers;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Who vouched for a refund of {@code paymentId}, checked before any transaction (D40 phase 1). */
    public record RefundApproval(UUID paymentId, UUID approverEmployeeId) {
    }

    /**
     * D40 phase 1, outside any transaction: {@code PAYMENT_REFUND} on the payment's store (404, 403), then an approver
     * holding {@code PAYMENT_REFUND_APPROVE} there who is not the refunder.
     *
     * @throws ApprovalDeniedException 403 for missing or refused approver credentials
     * @throws SettlementRuleException 422 {@code SELF_APPROVAL}
     */
    public RefundApproval authorize(UUID paymentId, String approverCode, String approverPin) {
        PosContext pos = authorized(paymentId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        UUID approver = approvers.verify(approverCode, approverPin, Permission.PAYMENT_REFUND_APPROVE,
                pos.storeId());
        if (approver.equals(pos.employeeId())) {
            throw SettlementRuleException.refundSelfApproval();
        }
        return new RefundApproval(paymentId, approver);
    }

    /**
     * D40 phase 2: the refund.
     *
     * @throws SettlementRuleException 422 {@code INVALID_AMOUNT}, {@code EXTERNAL_REFERENCE_REQUIRED},
     *         {@code NO_OPEN_CASH_SESSION}, {@code REFUND_EXCEEDS_REFUNDABLE},
     *         {@code CORRECTION_REQUIRES_FISCAL_ADAPTER}; customers' {@code AMOUNT_EXCEEDS_CREDIT}
     */
    @Transactional
    public RefundView refund(UUID paymentId, RefundRequest request, RefundApproval approval) {
        if (!approval.paymentId().equals(paymentId)) {
            throw new IllegalArgumentException("The approval is for payment " + approval.paymentId());
        }
        PosContext pos = authorized(paymentId);
        Payment payment = payments.findById(paymentId).orElseThrow();
        lockTargetOf(payment);
        Money amount = amountOf(request, payment);
        requireRefundable(payment, amount);
        if (payment.getTargetType() != TargetType.CREDIT_ACCOUNT) {
            requireNotInvoiced(payment, amount);
        }
        requireReferenceUnlessCash(payment, request.externalReference());
        OpenSessionView session = cash.openSessionOf(pos.storeId(), pos.employeeId())
                .orElseThrow(SettlementRuleException::noOpenSession);
        cash.lockSession(session.id());
        LocalDate businessDate = cash.findDay(session.cashDayId()).orElseThrow().businessDate();
        Refund refund = refunds.saveAndFlush(Refund.of(payment, amount, request.reason(),
                request.externalReference(), session.id(), pos.terminalId(), businessDate, pos.employeeId(),
                approval.approverEmployeeId(), clock.instant()));
        if (payment.getTargetType() == TargetType.CREDIT_ACCOUNT) {
            // D76: the customer's credit_account is held since the first lock; the DEBIT reopens what it settled
            customers.reverseCredit(refund.getId(), paymentId, amount, payment.getStoreId(), businessDate);
        }
        cash.recordRefund(new RefundCommand(session.id(), payment.getMethodCode(), amount, refund.getId(),
                pos.employeeId()));
        audit.refund(refund, details(refund));
        events.publishEvent(new RefundRecorded(refund.getId(), refund.getStoreId()));
        return refund.toView();
    }

    /** D42: the payment's store alone, checked against the POS session before anything is loaded or locked. */
    private PosContext authorized(UUID paymentId) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_REFUND);
        UUID storeId = payments.findStoreIdById(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(PAYMENT, paymentId));
        accessPolicy.requireSessionStore(pos, storeId, PAYMENT, paymentId);
        return pos;
    }

    /** D76: an order's payment starts at {@code order_settlement}, a debt payment at the customer's account. */
    private void lockTargetOf(Payment payment) {
        if (payment.getTargetType() == TargetType.CREDIT_ACCOUNT) {
            customers.lockAccount(payment.getCreditAccountId());
        } else {
            locks.lockOrder(payment.getOrderId());
        }
    }

    private static Money amountOf(RefundRequest request, Payment payment) {
        Money amount = PaymentAttemptService.money(request.amount(), payment.amount().currency());
        if (amount.amount().signum() <= 0) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT, "A refund amount must be positive.");
        }
        return amount;
    }

    /** D77: at most the payment less its earlier refunds (the database checks the same sum at commit). */
    private void requireRefundable(Payment payment, Money amount) {
        Money refunded = Money.of(refunds.refundedOf(payment.getId()), amount.currency());
        Money refundable = payment.amount().minus(refunded);
        if (amount.amount().compareTo(refundable.amount()) > 0) {
            throw SettlementRuleException.refundExceedsRefundable(payment.getId(), amount, refundable);
        }
    }

    /**
     * D77, P31: once an ISSUED document covers the payment's target, only its overpayment ({@code paid + credit −
     * due}, paid net of earlier refunds) goes back without a corrective document. An ITEMS partition is covered by its
     * own document; the ORDER residual and an AMOUNT partition (a payment grouping, D70) by the order's.
     */
    private void requireNotInvoiced(Payment payment, Money amount) {
        Bill bill = bills.of(payment.getOrderId());
        List<DocumentView> documents = fiscal.documentsOfOrder(payment.getOrderId());
        boolean covered;
        Money overpayment;
        if (payment.getTargetType() == TargetType.PARTITION) {
            BillPartition partition = bill.partition(payment.getPartitionId()).orElseThrow();
            covered = partition.getKind() == PartitionKind.ITEMS
                    ? issued(documents, "PARTITION", partition.getId()) : issued(documents, "ORDER", null);
            overpayment = bill.paidOf(partition).plus(bill.creditOn(partition.getId())).minus(partition.due());
        } else {
            covered = issued(documents, "ORDER", null);
            overpayment = bill.orderPaid().plus(bill.creditOn(null)).minus(bill.residualDue());
        }
        if (covered && amount.amount().compareTo(overpayment.amount().max(BigDecimal.ZERO)) > 0) {
            throw SettlementRuleException.correctionRequiresFiscalAdapter("Payment " + payment.getId()
                    + " pays an invoiced sale, of which only " + overpayment.amount().max(BigDecimal.ZERO)
                    .toPlainString() + " is an overpayment");
        }
    }

    private static boolean issued(List<DocumentView> documents, String sourceType, UUID sourceId) {
        return documents.stream().anyMatch(d -> "ISSUED".equals(d.status()) && sourceType.equals(d.sourceType())
                && (sourceId == null || sourceId.equals(d.sourceId())));
    }

    /** D77: a terminal's or provider's refund is made there first; its reference ties the two (D52, D78). */
    private void requireReferenceUnlessCash(Payment payment, String externalReference) {
        PaymentMethodKind kind = attempts.findById(payment.getAttemptId()).orElseThrow().getMethodKind();
        if (kind != PaymentMethodKind.CASH && (externalReference == null || externalReference.isBlank())) {
            throw SettlementRuleException.externalReferenceRequired(payment.getMethodCode());
        }
    }

    private static Map<String, Object> details(Refund refund) {
        Map<String, Object> details = new HashMap<>();
        details.put("paymentId", refund.getPaymentId().toString());
        details.put("methodCode", refund.getMethodCode());
        details.put("amount", refund.amount().amount().toPlainString());
        details.put("sessionId", refund.getSessionId().toString());
        if (refund.getOrderId() != null) {
            details.put("orderId", refund.getOrderId().toString());
        }
        if (refund.getCreditAccountId() != null) {
            details.put("customerId", refund.getCreditAccountId().toString());
        }
        return details;
    }
}
