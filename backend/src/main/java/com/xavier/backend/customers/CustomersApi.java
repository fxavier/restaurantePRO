package com.xavier.backend.customers;

import com.xavier.backend.customers.model.CustomerAddress;
import com.xavier.backend.customers.model.CustomerPhone;
import com.xavier.backend.customers.repository.CustomerAddressRepository;
import com.xavier.backend.customers.repository.CustomerPhoneRepository;
import com.xavier.backend.customers.service.CreditLedgerService;
import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What settlement asks the customers module for credit sales and debt payments (D66, D73, D74, D75). Reads are
 * {@code readOnly}; writes are {@code MANDATORY}, so settlement composes them in its own transaction, under the D76
 * lock order ({@code order_settlement} → {@code credit_account} → cash → fiscal). Nothing here authorizes: the calling
 * use case already did. Every write locks the customer's {@code credit_account} {@code FOR UPDATE} ({@code reserve}
 * then reads the {@code customer} row {@code FOR SHARE}, so a deactivation in flight is seen) and is idempotent
 * per its source id (charge, payment, refund): a repeat with the same values returns what was recorded and adds
 * nothing; a repeat with other values (customer, amount, document, payment) is a 409 {@code CREDIT_SOURCE_MISMATCH}
 * and changes nothing.
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
    private final CustomerAddressRepository addresses;
    private final CustomerPhoneRepository phones;

    CustomersApi(CreditLedgerService ledger, CustomerAddressRepository addresses, CustomerPhoneRepository phones) {
        this.phones = phones;
        this.ledger = ledger;
        this.addresses = addresses;
    }

    /** The buyer of a document: name, tax id and first billing address. 404 when missing, 422 when inactive. */
    @Transactional(readOnly = true)
    public CustomerSnapshot snapshot(UUID customerId) {
        return ledger.snapshot(customerId);
    }

    /**
     * The customer as {@link #snapshot} reads them, active or not; empty for an unknown id. Never throws, so a
     * caller's transaction is never marked for rollback by it (a debt payment's slip names an inactive customer too).
     */
    @Transactional(readOnly = true)
    public Optional<CustomerSnapshot> findCustomer(UUID customerId) {
        return ledger.findCustomer(customerId);
    }

    /** The customer's address {@code addressId}; empty when unknown or another customer's. Never throws. */
    @Transactional(readOnly = true)
    public Optional<AddressSnapshot> address(UUID customerId, UUID addressId) {
        if (customerId == null || addressId == null) {
            return Optional.empty(); // findById(null) would throw and mark a joined caller transaction rollback-only
        }
        return addresses.findById(addressId)
                .filter(address -> address.getCustomerId().equals(customerId))
                .map(CustomerAddress::toView)
                .map(view -> new AddressSnapshot(view.id(), view.kind(), view.line1(), view.line2(), view.city(),
                        view.postalCode(), view.country(), view.notes()));
    }

    /**
     * The customer's primary phone digits, else the lowest digits (S50: the phone a delivery snapshots); empty when
     * {@code customerId} is null or unknown, or the customer has no phone. Never throws.
     */
    @Transactional(readOnly = true)
    public Optional<String> primaryPhone(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return phones.findByCustomerIdOrderByPrimaryDescDigitsAsc(customerId).stream().findFirst()
                .map(CustomerPhone::getDigits);
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
     * credit not blocked, and available ≥ amount. The same {@code chargeId} returns its ACTIVE reservation; a
     * CONSUMED or RELEASED one is a 409 {@code RESERVATION_NOT_ACTIVE} (a cancelled charge reserves under a new id).
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
     * The credit sale's DEBIT once its document is issued; consumes the reservation, whose amount it must repeat
     * (409 {@code CREDIT_SOURCE_MISMATCH} otherwise). Idempotent per charge: the sweeper and the sale's last step may
     * both post it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postDebit(UUID chargeId, UUID documentId, UUID storeId, LocalDate businessDate,
                                     Money amount) {
        return ledger.postDebit(chargeId, documentId, storeId, businessDate, amount);
    }

    /**
     * A debt payment's CREDIT, settling the open debits FIFO when {@code debitEntryIds} is empty, else the named ones
     * in that order (null means none). 422 {@code AMOUNT_EXCEEDS_DEBT} beyond the debt (or the named debits).
     * Idempotent per payment.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postCredit(UUID paymentId, UUID customerId, Money amount, List<UUID> debitEntryIds,
                                      UUID storeId, LocalDate businessDate) {
        return ledger.postCredit(paymentId, customerId, amount,
                debitEntryIds == null ? List.of() : List.copyOf(debitEntryIds), storeId, businessDate);
    }

    /**
     * The refund of a debt payment: a DEBIT with source REFUND, on the refund's store and business day (P37), that
     * reopens what the payment settled, latest debit first. 422 {@code AMOUNT_EXCEEDS_CREDIT} beyond what is left to
     * reverse. Idempotent per refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView reverseCredit(UUID refundId, UUID paymentId, Money amount, UUID storeId,
                                         LocalDate businessDate) {
        return ledger.reverseCredit(refundId, paymentId, amount, storeId, businessDate);
    }

    /**
     * The open amount of each of {@code debitEntryIds} that is a sale debit of this customer (zero once fully
     * settled); an unknown id, or a debit of another customer, is absent. For a debt payment to check the debits it
     * names before any money is taken (D75).
     */
    @Transactional(readOnly = true)
    public Map<UUID, Money> openDebits(UUID customerId, List<UUID> debitEntryIds) {
        return ledger.openDebits(customerId, debitEntryIds);
    }

    /** The open debt (balance), zero without an account. */
    @Transactional(readOnly = true)
    public Money debtOf(UUID customerId) {
        return ledger.debtOf(customerId);
    }
}
