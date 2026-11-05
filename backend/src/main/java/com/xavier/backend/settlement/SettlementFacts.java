package com.xavier.backend.settlement;

import com.xavier.backend.cash.CashApi;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
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
 * method is read-only and authorizes nothing (reporting's use case did). Refunds come in S30 and credit charges in
 * S29: until then they have no facts.
 */
@Service
public class SettlementFacts {

    /** Stands for "no session" in an IN list, which may not be empty. */
    private static final UUID NO_SESSION = new UUID(0, 0);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final CashApi cash;

    SettlementFacts(PaymentRepository payments, PaymentAttemptRepository attempts, CashApi cash) {
        this.payments = payments;
        this.attempts = attempts;
        this.cash = cash;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentFact> paymentFact(UUID paymentId) {
        return payments.findById(paymentId).map(this::fact);
    }

    /** Empty until S30 records refunds. */
    @Transactional(readOnly = true)
    public Optional<RefundFact> refundFact(UUID refundId) {
        return Optional.empty();
    }

    /** Empty until S29 posts credit charges. */
    @Transactional(readOnly = true)
    public Optional<CreditChargeFact> creditChargeFact(UUID chargeId) {
        return Optional.empty();
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

    @Transactional(readOnly = true)
    public Page<UUID> refundIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        return Page.empty(page);
    }

    @Transactional(readOnly = true)
    public Page<UUID> creditChargeIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        return Page.empty(page);
    }

    private PaymentFact fact(Payment payment) {
        UUID terminal = payment.getTerminalId() != null ? payment.getTerminalId()
                : attempts.findById(payment.getAttemptId()).map(PaymentAttempt::getTerminalId).orElse(null);
        LocalDate businessDate = payment.getBusinessDate() != null ? payment.getBusinessDate()
                : cash.sessionDay(payment.getSessionId()).businessDate();
        return new PaymentFact(payment.getId(), payment.getStoreId(), businessDate,
                payment.getConfirmedAt(), payment.getMethodCode(), payment.amount(), payment.getTargetType().name(),
                payment.getOrderId(), payment.getPartitionId(), null, payment.getCollectedByEmployeeId(), terminal);
    }
}
