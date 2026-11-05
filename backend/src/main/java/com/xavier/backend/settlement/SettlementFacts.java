package com.xavier.backend.settlement;

import com.xavier.backend.cash.CashApi;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.repository.RefundRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement's immutable facts for reporting only (D66, D84): reporting's listeners pull them on
 * {@code PaymentRecorded}, {@code RefundRecorded} and {@code CreditChargePosted}, and a rebuild pages the ids. Every
 * method is read-only and authorizes nothing (reporting's use case did). A credit charge is a fact once POSTED (D74): it is never money received, so it is never a payment fact.
 */
@Service
public class SettlementFacts {

    /** Stands for "no session" in an IN list, which may not be empty. */
    private static final UUID NO_SESSION = new UUID(0, 0);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final CashApi cash;
    private final CreditChargeRepository charges;
    private final RefundRepository refunds;

    SettlementFacts(PaymentRepository payments, PaymentAttemptRepository attempts, CashApi cash,
                    CreditChargeRepository charges, RefundRepository refunds) {
        this.refunds = refunds;
        this.payments = payments;
        this.attempts = attempts;
        this.cash = cash;
        this.charges = charges;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentFact> paymentFact(UUID paymentId) {
        return payments.findById(paymentId).map(this::fact);
    }

    /**
     * A refund (D77): its amount is the money given back, positive (reporting signs it); {@code businessDate} and
     * {@code terminalId} are the refunder's session's day and terminal; {@code customerId} is set for the refund of a
     * debt payment, which pays no order.
     */
    @Transactional(readOnly = true)
    public Optional<RefundFact> refundFact(UUID refundId) {
        return refunds.findById(refundId).map(r -> new RefundFact(r.getId(), r.getPaymentId(), r.getStoreId(),
                r.getBusinessDate(), r.getConfirmedAt(), r.getMethodCode(), r.amount(), r.getOrderId(),
                r.getPartitionId(), r.getCreditAccountId(), r.getOperatorEmployeeId(), r.getApproverEmployeeId(),
                r.getTerminalId()));
    }

    /** A POSTED credit charge; empty for one still RESERVED or RELEASED (never on the customer's account). */
    @Transactional(readOnly = true)
    public Optional<CreditChargeFact> creditChargeFact(UUID chargeId) {
        return charges.findById(chargeId).filter(c -> c.getStatus() == CreditChargeStatus.POSTED)
                .map(c -> new CreditChargeFact(c.getId(), c.getStoreId(), c.getBusinessDate(), c.getPostedAt(),
                        c.amount(), c.getOrderId(), c.getPartitionId(), c.getCustomerId(), c.getDocumentId(),
                        c.getPostedByEmployeeId(), c.getTerminalId()));
    }

    /**
     * The ids of the store's payments whose business day is in {@code [from, to]}, oldest first (ids are UUIDv7); the
     * page's sort is ignored. A payment recorded before R3 stores no business day: it takes its session's cash day,
     * resolved at read time (no fact row is updated).
     */
    @Transactional(readOnly = true)
    public Page<UUID> paymentIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        List<UUID> sessions = cash.sessionIdsOfDays(storeId, from, to);
        return payments.idsOf(storeId, from, to, sessions.isEmpty() ? List.of(NO_SESSION) : sessions,
                Pageable.ofSize(page.getPageSize()).withPage(page.getPageNumber()));
    }

    /** The ids of the store's refunds of business days {@code [from, to]}, oldest first. */
    @Transactional(readOnly = true)
    public Page<UUID> refundIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        return refunds.idsOf(storeId, from, to, Pageable.ofSize(page.getPageSize()).withPage(page.getPageNumber()));
    }

    /** The ids of the store's POSTED charges of business days {@code [from, to]}, oldest first. */
    @Transactional(readOnly = true)
    public Page<UUID> creditChargeIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        return charges.postedIds(storeId, from, to, Pageable.ofSize(page.getPageSize())
                .withPage(page.getPageNumber()));
    }

    private PaymentFact fact(Payment payment) {
        UUID terminal = payment.getTerminalId() != null ? payment.getTerminalId()
                : attempts.findById(payment.getAttemptId()).map(PaymentAttempt::getTerminalId).orElse(null);
        LocalDate businessDate = payment.getBusinessDate() != null ? payment.getBusinessDate()
                : cash.sessionDay(payment.getSessionId()).businessDate();
        return new PaymentFact(payment.getId(), payment.getStoreId(), businessDate,
                payment.getConfirmedAt(), payment.getMethodCode(), payment.amount(), payment.getTargetType().name(),
                payment.getOrderId(), payment.getPartitionId(), payment.getCreditAccountId(),
                payment.getCollectedByEmployeeId(), terminal);
    }
}
