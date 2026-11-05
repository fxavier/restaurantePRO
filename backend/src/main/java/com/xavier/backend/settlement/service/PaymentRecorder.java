package com.xavier.backend.settlement.service;

import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentAllocation;
import com.xavier.backend.settlement.model.PaymentAttempt;
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
 * ({@code UNIQUE attempt_id}), its allocation to its target (the order or a partition, D69) and its cash receipt
 * ({@code UNIQUE source}), and publishes {@link PaymentRecorded} (D84), in the
 * caller's transaction, under the order's settlement lock. Every confirmed payment is a {@code PAYMENT_RECEIPT} of its
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
    private final Clock clock;

    PaymentRecorder(PaymentRepository payments, PaymentAllocationRepository allocations, CashApi cash,
                    SettlementAudit audit, ApplicationEventPublisher events, Clock clock) {
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
        cash.recordReceipt(new ReceiptCommand(payment.getSessionId(), payment.getMethodCode(), payment.amount(),
                payment.getId(), payment.getCollectedByEmployeeId()));
        audit.attempt("PAYMENT_CONFIRMED", succeeded.getId(), succeeded.getStoreId(), succeeded.getOrderId(),
                succeeded.getTerminalId(), null, null, Map.of("paymentId", payment.getId().toString(),
                        "methodCode", payment.getMethodCode(), "amount", payment.amount().amount().toPlainString()));
        events.publishEvent(new PaymentRecorded(payment.getId(), succeeded.getStoreId()));
    }
}
