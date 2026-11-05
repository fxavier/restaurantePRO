package com.xavier.backend.customers;

import com.xavier.backend.customers.service.CreditLedgerService;
import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What settlement asks the customers module for credit sales and debt payments (D66, D73, D74, D75). Reads are
 * {@code readOnly}; writes are {@code MANDATORY}, so settlement composes them in its own transaction, under the D76
 * lock order ({@code order_settlement} → {@code credit_account} → cash → fiscal). Nothing here authorizes: the calling
 * use case already did. Every write locks the customer's {@code credit_account} {@code FOR UPDATE} and is idempotent
 * per its source id (charge, payment, refund): a repeat returns what was recorded and adds nothing.
 *
 * <p>Errors: unknown customer, reservation or entry → 404; a stable-coded 422
 * ({@link com.xavier.backend.customers.exception.CustomerRuleException}): {@code CUSTOMER_INACTIVE},
 * {@code CREDIT_ACCOUNT_MISSING}, {@code CREDIT_BLOCKED}, {@code CREDIT_LIMIT_EXCEEDED} (property {@code available}),
 * {@code AMOUNT_EXCEEDS_DEBT} ({@code debt}), {@code AMOUNT_EXCEEDS_CREDIT} ({@code refundable}); a closed reservation
 * → 409 {@code RESERVATION_NOT_ACTIVE}. Amounts are in the account currency (mixing currencies is an
 * {@link IllegalArgumentException}).
 */
@Service
public class CustomersApi {

    private final CreditLedgerService ledger;

    CustomersApi(CreditLedgerService ledger) {
        this.ledger = ledger;
    }

    /** The buyer of a document: name, tax id and first billing address. 404 when missing, 422 when inactive. */
    @Transactional(readOnly = true)
    public CustomerSnapshot snapshot(UUID customerId) {
        return ledger.snapshot(customerId);
    }

    /** Limit, balance (Σ ledger), reserved, available and block. 422 {@code CREDIT_ACCOUNT_MISSING}. */
    @Transactional(readOnly = true)
    public CreditView credit(UUID customerId) {
        return ledger.credit(customerId);
    }

    /**
     * Locks the customer's {@code credit_account} {@code FOR UPDATE} until the caller's transaction ends and returns
     * its credit as seen under the lock (D66, D75: a debt payment starts here).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditView lockAccount(UUID customerId) {
        return ledger.lockAccount(customerId);
    }

    /**
     * Holds {@code amount} for {@code chargeId}: under the account lock it re-checks that the customer is active, the
     * credit not blocked, and available ≥ amount. The same {@code chargeId} returns its reservation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationView reserve(UUID customerId, Money amount, UUID chargeId) {
        return ledger.reserve(customerId, amount, chargeId);
    }

    /** ACTIVE → RELEASED (a cancelled charge). No-op when already released; 409 when consumed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID chargeId) {
        ledger.release(chargeId);
    }

    /**
     * The credit sale's DEBIT once its document is issued; consumes the reservation (same amount). Idempotent per
     * charge: the sweeper and the sale's last step may both post it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postDebit(UUID chargeId, UUID documentId, UUID storeId, LocalDate businessDate,
                                     Money amount) {
        return ledger.postDebit(chargeId, documentId, storeId, businessDate, amount);
    }

    /**
     * A debt payment's CREDIT, settling the open debits FIFO when {@code debitEntryIds} is empty, else the named ones
     * in that order. 422 {@code AMOUNT_EXCEEDS_DEBT} beyond the debt (or the named debits). Idempotent per payment.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postCredit(UUID paymentId, UUID customerId, Money amount, List<UUID> debitEntryIds,
                                      UUID storeId, LocalDate businessDate) {
        return ledger.postCredit(paymentId, customerId, amount, List.copyOf(debitEntryIds), storeId, businessDate);
    }

    /**
     * The refund of a debt payment: a DEBIT with source REFUND that reopens what the payment settled, latest debit
     * first. 422 {@code AMOUNT_EXCEEDS_CREDIT} beyond what is left to reverse. Idempotent per refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView reverseCredit(UUID refundId, UUID paymentId, Money amount) {
        return ledger.reverseCredit(refundId, paymentId, amount);
    }

    /** The open debt (balance), zero without an account. */
    @Transactional(readOnly = true)
    public Money debtOf(UUID customerId) {
        return ledger.debtOf(customerId);
    }
}
