package com.xavier.backend.customers.service;

import com.xavier.backend.customers.CreditView;
import com.xavier.backend.customers.CustomerSnapshot;
import com.xavier.backend.customers.LedgerEntryView;
import com.xavier.backend.customers.ReservationView;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.customers.exception.CustomersNotFoundException;
import com.xavier.backend.customers.model.AddressKind;
import com.xavier.backend.customers.model.Allocation;
import com.xavier.backend.customers.model.Allocation.Part;
import com.xavier.backend.customers.model.Allocation.Slot;
import com.xavier.backend.customers.model.CreditAccount;
import com.xavier.backend.customers.model.CreditLedgerEntry;
import com.xavier.backend.customers.model.CreditReservation;
import com.xavier.backend.customers.model.CreditSettlement;
import com.xavier.backend.customers.model.Customer;
import com.xavier.backend.customers.model.CustomerAddress;
import com.xavier.backend.customers.model.EntryKind;
import com.xavier.backend.customers.model.EntrySource;
import com.xavier.backend.customers.model.ReservationStatus;
import com.xavier.backend.customers.repository.CreditAccountRepository;
import com.xavier.backend.customers.repository.CreditLedgerEntryRepository;
import com.xavier.backend.customers.repository.CreditReservationRepository;
import com.xavier.backend.customers.repository.CreditSettlementRepository;
import com.xavier.backend.customers.repository.CustomerAddressRepository;
import com.xavier.backend.customers.repository.CustomerRepository;
import com.xavier.backend.customers.repository.EntryAmount;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit ledger use cases behind {@link com.xavier.backend.customers.CustomersApi} (D73). They do not authorize:
 * settlement's use case already did. Every write locks the customer's {@code credit_account} first (D76) and decides
 * on what it reads under that lock, so two terminals never spend the same available credit (§8.4). Every write is
 * idempotent per its source id, backed by {@code credit_reservation_charge_uk} and
 * {@code credit_ledger_entry_source_uk}.
 */
@Service
public class CreditLedgerService {

    static final String ENTRY = "CreditLedgerEntry";
    static final String RESERVATION = "CreditReservation";
    private static final String CHARGE = "credit charge";
    private static final String PAYMENT = "debt payment";
    private static final String REFUND = "refund";

    private final CustomerRepository customers;
    private final CustomerAddressRepository addresses;
    private final CreditAccountRepository accounts;
    private final CreditReservationRepository reservations;
    private final CreditLedgerEntryRepository entries;
    private final CreditSettlementRepository settlements;
    private final CreditLocks locks;
    private final CreditBalances balances;
    private final OrganizationApi organization;
    private final Clock clock;

    CreditLedgerService(CustomerRepository customers, CustomerAddressRepository addresses,
                        CreditAccountRepository accounts, CreditReservationRepository reservations,
                        CreditLedgerEntryRepository entries, CreditSettlementRepository settlements,
                        CreditLocks locks, CreditBalances balances, OrganizationApi organization, Clock clock) {
        this.customers = customers;
        this.addresses = addresses;
        this.accounts = accounts;
        this.reservations = reservations;
        this.entries = entries;
        this.settlements = settlements;
        this.locks = locks;
        this.balances = balances;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CustomerSnapshot snapshot(UUID customerId) {
        Customer customer = activeCustomer(customerId);
        return new CustomerSnapshot(customer.getId(), customer.getName(), customer.getTaxId(),
                addresses.findFirstByCustomerIdAndKindOrderByIdAsc(customerId, AddressKind.BILLING)
                        .map(CustomerAddress::toView).orElse(null));
    }

    @Transactional(readOnly = true)
    public CreditView credit(UUID customerId) {
        requireCustomer(customerId);
        return balances.view(accounts.findById(customerId)
                .orElseThrow(() -> CustomerRuleException.accountMissing(customerId)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CreditView lockAccount(UUID customerId) {
        requireCustomer(customerId);
        return balances.view(lockedAccount(customerId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, Money> openDebits(UUID customerId, List<UUID> debitEntryIds) {
        requireCustomer(customerId);
        if (debitEntryIds.isEmpty()) {
            return Map.of();
        }
        java.util.Currency currency = accounts.findById(customerId).map(CreditAccount::currency)
                .orElseGet(() -> organization.companyProfile().currency());
        return entries.openAmountsOf(customerId, debitEntryIds).stream().collect(Collectors.toMap(
                EntryAmount::getEntryId, open -> Money.of(open.getAmount(), currency)));
    }

    @Transactional(readOnly = true)
    public Money debtOf(UUID customerId) {
        requireCustomer(customerId);
        return balances.balance(customerId, accounts.findById(customerId).map(CreditAccount::currency)
                .orElseGet(() -> organization.companyProfile().currency()));
    }

    /**
     * Holds {@code amount} of the available credit for {@code chargeId}. The same charge gets its ACTIVE reservation
     * back; a closed one is a 409, and other values a 409 {@code CREDIT_SOURCE_MISMATCH}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationView reserve(UUID customerId, Money amount, UUID chargeId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new CustomersNotFoundException(CustomerService.ENTITY, customerId));
        CreditAccount account = lockedAccount(customerId);
        if (!locks.customerForShare(customer).isActive()) {
            throw CustomerRuleException.inactive(customerId);
        }
        requireCurrency(amount, account);
        Optional<CreditReservation> existing = reservations.findByChargeId(chargeId);
        if (existing.isPresent()) {
            return sameCharge(existing.get(), customerId, amount).toView();
        }
        if (account.isCreditBlocked()) {
            throw CustomerRuleException.blocked(customerId);
        }
        Money available = balances.view(account).available();
        if (amount.amount().compareTo(available.amount()) > 0) {
            throw CustomerRuleException.limitExceeded(amount, available);
        }
        return reservations.saveAndFlush(CreditReservation.hold(customerId, amount, chargeId, clock.instant()))
                .toView();
    }

    /** ACTIVE → RELEASED; a released reservation stays released. A consumed one is a 409. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID chargeId) {
        CreditReservation reservation = lockedReservation(chargeId);
        reservation.release(clock.instant());
        reservations.saveAndFlush(reservation);
    }

    /** The sale's DEBIT, which consumes its reservation; the same charge gets its entry back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postDebit(UUID chargeId, UUID documentId, UUID storeId, LocalDate businessDate,
                                     Money amount) {
        CreditReservation reservation = lockedReservation(chargeId);
        Optional<CreditLedgerEntry> posted = entry(EntrySource.CREDIT_CHARGE, chargeId, EntryKind.DEBIT);
        if (posted.isPresent()) {
            return replay(posted.get(), CHARGE, chargeId, reservation.getCustomerId(), amount, documentId, null);
        }
        if (!reservation.amount().equals(amount)) {
            throw CustomersConflictException.sourceMismatch(CHARGE, chargeId);
        }
        reservation.consume(clock.instant());
        reservations.saveAndFlush(reservation);
        return entries.saveAndFlush(CreditLedgerEntry.saleDebit(reservation, documentId, storeId, businessDate,
                clock.instant())).toView();
    }

    /**
     * The debt payment's CREDIT and its settlements: FIFO over the open debits, or over {@code debitEntryIds} in that
     * order. At most the open debt (P33: no advance).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView postCredit(UUID paymentId, UUID customerId, Money amount, List<UUID> debitEntryIds,
                                      UUID storeId, LocalDate businessDate) {
        requireCustomer(customerId);
        CreditAccount account = lockedAccount(customerId);
        requireCurrency(amount, account);
        Optional<CreditLedgerEntry> posted = entry(EntrySource.PAYMENT, paymentId, EntryKind.CREDIT);
        if (posted.isPresent()) {
            return replay(posted.get(), PAYMENT, paymentId, customerId, amount, null, null);
        }
        Money debt = balances.balance(customerId, account.currency());
        if (amount.amount().compareTo(debt.amount()) > 0) {
            throw CustomerRuleException.exceedsDebt(amount, debt);
        }
        List<Slot> debits = debitEntryIds.isEmpty() ? slots(entries.openDebits(customerId))
                : namedDebits(customerId, debitEntryIds);
        BigDecimal named = debits.stream().map(Slot::capacity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.amount().compareTo(named) > 0) {
            throw CustomerRuleException.exceedsDebt(amount, Money.of(named, account.currency()));
        }
        CreditLedgerEntry credit = entries.saveAndFlush(CreditLedgerEntry.paymentCredit(customerId, paymentId, amount,
                storeId, businessDate, clock.instant()));
        for (Part part : Allocation.fillInOrder(amount.amount(), debits)) {
            settlements.save(CreditSettlement.settles(credit.getId(), part.entryId(), part.amount()));
        }
        settlements.flush();
        return credit.toView();
    }

    /** The refund of a debt payment: a REFUND DEBIT that reopens what the payment settled, latest debit first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntryView reverseCredit(UUID refundId, UUID paymentId, Money amount, UUID storeId,
                                         LocalDate businessDate) {
        CreditLedgerEntry payment = entry(EntrySource.PAYMENT, paymentId, EntryKind.CREDIT)
                .orElseThrow(() -> new CustomersNotFoundException(ENTRY, "of payment " + paymentId));
        requireCurrency(amount, lockedAccount(payment.getCustomerId()));
        Optional<CreditLedgerEntry> posted = entry(EntrySource.REFUND, refundId, EntryKind.DEBIT);
        if (posted.isPresent()) {
            return replay(posted.get(), REFUND, refundId, payment.getCustomerId(), amount, null, payment.getId());
        }
        List<Slot> settled = slots(entries.settledBy(payment.getId()));
        Money refundable = Money.of(settled.stream().map(Slot::capacity).reduce(BigDecimal.ZERO, BigDecimal::add),
                amount.currency());
        if (amount.amount().compareTo(refundable.amount()) > 0) {
            throw CustomerRuleException.exceedsCredit(amount, refundable);
        }
        CreditLedgerEntry reversal = entries.saveAndFlush(CreditLedgerEntry.refundDebit(payment, refundId, amount,
                storeId, businessDate, clock.instant()));
        for (Part part : Allocation.fillInOrder(amount.amount(), settled)) {
            settlements.save(CreditSettlement.reopens(payment.getId(), part.entryId(), reversal.getId(),
                    part.amount()));
        }
        settlements.flush();
        return reversal.toView();
    }

    private List<Slot> namedDebits(UUID customerId, List<UUID> ids) {
        Map<UUID, BigDecimal> open = entries.openAmountsOf(customerId, ids).stream()
                .collect(Collectors.toMap(EntryAmount::getEntryId, EntryAmount::getAmount));
        return ids.stream().distinct().map(id -> new Slot(id, Optional.ofNullable(open.get(id))
                .orElseThrow(() -> new CustomersNotFoundException(ENTRY, id)))).toList();
    }

    private static List<Slot> slots(List<EntryAmount> amounts) {
        return amounts.stream().map(a -> new Slot(a.getEntryId(), a.getAmount())).toList();
    }

    private Optional<CreditLedgerEntry> entry(EntrySource source, UUID sourceId, EntryKind kind) {
        return entries.findBySourceTypeAndSourceIdAndKind(source, sourceId, kind);
    }

    /** The reservation re-read under its account's lock, so a concurrent release or post is seen. */
    private CreditReservation lockedReservation(UUID chargeId) {
        CreditReservation reservation = reservations.findByChargeId(chargeId)
                .orElseThrow(() -> new CustomersNotFoundException(RESERVATION, "of charge " + chargeId));
        lockedAccount(reservation.getCustomerId());
        return locks.reread(reservation);
    }

    private CreditAccount lockedAccount(UUID customerId) {
        return locks.account(customerId).orElseThrow(() -> CustomerRuleException.accountMissing(customerId));
    }

    private static void requireCurrency(Money amount, CreditAccount account) {
        if (!amount.currency().equals(account.currency())) {
            throw new IllegalArgumentException("The credit account of customer " + account.getCustomerId() + " is in "
                    + account.currency().getCurrencyCode() + ", not " + amount.currency().getCurrencyCode());
        }
    }

    /**
     * A replay returns what was recorded only when it repeats it. Checked under the account lock, so two different
     * calls for one id never both pass.
     */
    private static LedgerEntryView replay(CreditLedgerEntry recorded, String source, UUID sourceId, UUID customerId,
                                          Money amount, UUID documentId, UUID reversedEntryId) {
        if (!recorded.repeats(customerId, amount, documentId, reversedEntryId)) {
            throw CustomersConflictException.sourceMismatch(source, sourceId);
        }
        return recorded.toView();
    }

    private static CreditReservation sameCharge(CreditReservation reservation, UUID customerId, Money amount) {
        if (!reservation.getCustomerId().equals(customerId) || !reservation.amount().equals(amount)) {
            throw CustomersConflictException.sourceMismatch(CHARGE, reservation.getChargeId());
        }
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw CustomersConflictException.reservationClosed(reservation.getChargeId(), reservation.getStatus(),
                    "be reserved again");
        }
        return reservation;
    }

    private Customer activeCustomer(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new CustomersNotFoundException(CustomerService.ENTITY, customerId));
        if (!customer.isActive()) {
            throw CustomerRuleException.inactive(customerId);
        }
        return customer;
    }

    private void requireCustomer(UUID customerId) {
        if (!customers.existsById(customerId)) {
            throw new CustomersNotFoundException(CustomerService.ENTITY, customerId);
        }
    }

}
