package com.xavier.backend.settlement.service;

import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentAllocation;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.TargetType;
import com.xavier.backend.settlement.model.event.CashDrawerKickRequested;
import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.settlement.model.event.PaymentRecorded;
import com.xavier.backend.settlement.repository.PaymentAllocationRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D50 §7.5, the one place money is recorded: a SUCCEEDED attempt becomes its {@link Payment}
 * ({@code UNIQUE attempt_id}), its allocation to its target (the order, a partition, or a customer's credit account,
 * D69) and its cash receipt ({@code UNIQUE source}), and publishes {@link PaymentRecorded} (D84), in the caller's
 * transaction, under its target's lock. A debt payment (D75) also posts the customer's CREDIT
 * ({@code CustomersApi.postCredit}, before the receipt: D76) and requests its non-fiscal slip
 * ({@link ReceiptSlipRequested}), once with the payment. A CASH-kind payment on a terminal also opens its drawer
 * ({@link CashDrawerKickRequested}, D158). Every confirmed payment is a {@code PAYMENT_RECEIPT} of its
 * method in the attempt's session (D52), so the closing compares card totals too. Nothing else creates a receipt
 * (§7.7: issuing a document never does).
 */
@Component
class PaymentRecorder {

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final CashApi cash;
    private final SettlementAudit audit;
    private final ApplicationEventPublisher events;
    private final CustomersApi customers;
    private final ReceiptSlips slips;
    private final Clock clock;

    PaymentRecorder(PaymentRepository payments, PaymentAllocationRepository allocations, CashApi cash,
                    SettlementAudit audit, ApplicationEventPublisher events, CustomersApi customers,
                    ReceiptSlips slips, Clock clock) {
        this.customers = customers;
        this.slips = slips;
        this.events = events;
        this.payments = payments;
        this.allocations = allocations;
        this.cash = cash;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void record(PaymentAttempt succeeded) {
        Instant now = clock.instant();
        // D51/D76: order_settlement is held; the session lock comes next, as recordReceipt takes it anyway
        SessionView session = cash.lockSession(succeeded.getSessionId());
        LocalDate businessDate = cash.findDay(session.cashDayId()).orElseThrow().businessDate();
        Payment payment = payments.saveAndFlush(Payment.of(succeeded, businessDate, now));
        allocations.saveAndFlush(PaymentAllocation.toTarget(payment, now));
        if (payment.getTargetType() == TargetType.CREDIT_ACCOUNT) {
            // D75: the customer's credit_account is held since the command's first lock; the CREDIT settles debits
            customers.postCredit(payment.getId(), payment.getCreditAccountId(), payment.amount(),
                    succeeded.debitEntryIds(), payment.getStoreId(), businessDate);
        }
        cash.recordReceipt(new ReceiptCommand(payment.getSessionId(), payment.getMethodCode(), payment.amount(),
                payment.getId(), payment.getCollectedByEmployeeId()));
        audit.attempt("PAYMENT_CONFIRMED", succeeded.getId(), succeeded.getStoreId(), succeeded.getOrderId(),
                succeeded.getTerminalId(), null, null, Map.of("paymentId", payment.getId().toString(),
                        "methodCode", payment.getMethodCode(), "amount", payment.amount().amount().toPlainString()));
        events.publishEvent(new PaymentRecorded(payment.getId(), succeeded.getStoreId()));
        if (succeeded.getMethodKind() == PaymentMethodKind.CASH) {
            // an attempt always has its terminal (PaymentAttempt.start requires it)
            events.publishEvent(new CashDrawerKickRequested("PAYMENT", payment.getId(), payment.getStoreId(),
                    succeeded.getTerminalId()));
        }
        if (payment.getTargetType() == TargetType.CREDIT_ACCOUNT) {
            events.publishEvent(new ReceiptSlipRequested(payment.getId(), payment.getStoreId(),
                    payment.getTerminalId(), slips.lines(payment, customers.debtOf(payment.getCreditAccountId()))));
        }
    }
}
