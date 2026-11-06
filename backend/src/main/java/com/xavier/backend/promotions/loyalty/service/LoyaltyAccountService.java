package com.xavier.backend.promotions.loyalty.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.promotions.LoyaltyBalance;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyNotFoundException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
import com.xavier.backend.promotions.loyalty.model.LoyaltyAccount;
import com.xavier.backend.promotions.loyalty.model.LoyaltyPointEntry;
import com.xavier.backend.promotions.loyalty.model.dto.AccountView;
import com.xavier.backend.promotions.loyalty.model.dto.AdjustmentRequest;
import com.xavier.backend.promotions.loyalty.model.dto.EntryView;
import com.xavier.backend.promotions.loyalty.model.dto.ReasonRequest;
import com.xavier.backend.promotions.loyalty.model.dto.StatementLine;
import com.xavier.backend.promotions.loyalty.model.dto.StatementView;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyAccountRepository;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyPointEntryRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loyalty members (D110): explicit enrollment at the POS, suspension and resumption, balances, statements and manual
 * adjustments. Customers and the program are company-wide (P38), so the BackOffice checks the permission
 * installation-wide or in any store; the POS needs an employee session on a terminal ({@code requirePos}). Every write
 * is audited with the customer as the entity.
 */
@Service
public class LoyaltyAccountService {

    private static final String CUSTOMER = "Customer";

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyPointEntryRepository entries;
    private final LoyaltyLedgerService ledger;
    private final LoyaltyLocks locks;
    private final CustomersApi customers;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    LoyaltyAccountService(LoyaltyAccountRepository accounts, LoyaltyPointEntryRepository entries,
                          LoyaltyLedgerService ledger, LoyaltyLocks locks, CustomersApi customers,
                          AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.ledger = ledger;
        this.locks = locks;
        this.customers = customers;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** POS enrollment of an active customer (404 unknown, 422 {@code CUSTOMER_INACTIVE}, 409 already enrolled). */
    @Transactional
    public AccountView enroll(UUID customerId) {
        PosContext pos = accessPolicy.requirePos(Permission.LOYALTY_ENROLL);
        customers.snapshot(customerId);
        if (accounts.existsById(customerId)) {
            throw LoyaltyConflictException.alreadyEnrolled(customerId);
        }
        LedgerActor actor = LedgerActor.of(accessPolicy);
        LoyaltyAccount account = UniqueConstraints.saveAndFlush(accounts,
                LoyaltyAccount.enroll(customerId, actor.kind(), actor.id(), clock.instant()),
                () -> LoyaltyConflictException.alreadyEnrolled(customerId), LoyaltyAccount.PK);
        auditLog.recordAsCurrentActor("LOYALTY_ENROLLED", CUSTOMER, customerId.toString(), pos.storeId(), null,
                null, Map.of());
        return view(account);
    }

    @Transactional
    public AccountView suspend(UUID customerId, ReasonRequest request) {
        accessPolicy.requireInAnyStore(Permission.LOYALTY_ENROLL);
        String reason = requireReason(request.reason(), "suspension");
        LoyaltyAccount account = lockedAccount(customerId);
        account.suspend(reason);
        accounts.saveAndFlush(account);
        auditLog.recordAsCurrentActor("LOYALTY_SUSPENDED", CUSTOMER, customerId.toString(), null, null, reason,
                Map.of());
        return view(account);
    }

    @Transactional
    public AccountView resume(UUID customerId, ReasonRequest request) {
        accessPolicy.requireInAnyStore(Permission.LOYALTY_ENROLL);
        String reason = requireReason(request.reason(), "resumption");
        LoyaltyAccount account = lockedAccount(customerId);
        account.resume();
        accounts.saveAndFlush(account);
        auditLog.recordAsCurrentActor("LOYALTY_RESUMED", CUSTOMER, customerId.toString(), null, null, reason,
                Map.of());
        return view(account);
    }

    /** {@code points} with a reason (§8.4); a debit beyond the balance is a 422 {@code LOYALTY_INSUFFICIENT_POINTS}. */
    @Transactional
    public EntryView adjust(UUID customerId, AdjustmentRequest request) {
        accessPolicy.requireInAnyStore(Permission.LOYALTY_ADJUST);
        String reason = requireReason(request.reason(), "loyalty adjustment");
        LoyaltyPointEntry entry = ledger.adjust(lockedAccount(customerId), request.points(), reason);
        auditLog.recordAsCurrentActor("LOYALTY_ADJUSTED", CUSTOMER, customerId.toString(), null, null, reason,
                Map.of("entryId", entry.getId().toString(), "points", entry.getPoints()));
        return entry.toView();
    }

    @Transactional(readOnly = true)
    public LoyaltyBalance balanceAtPos(UUID customerId) {
        accessPolicy.requirePos(Permission.LOYALTY_VIEW);
        requireCustomer(customerId);
        return ledger.balance(customerId);
    }

    @Transactional(readOnly = true)
    public LoyaltyBalance balance(UUID customerId) {
        accessPolicy.requireInAnyStore(Permission.LOYALTY_VIEW);
        requireCustomer(customerId);
        return ledger.balance(customerId);
    }

    /** Every entry in {@code [from, to]} (default: since the start, until now) with its running balance. */
    @Transactional(readOnly = true)
    public StatementView statement(UUID customerId, Instant from, Instant to) {
        accessPolicy.requireInAnyStore(Permission.LOYALTY_VIEW);
        requireCustomer(customerId);
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? clock.instant() : to;
        if (start.isAfter(end)) {
            throw LoyaltyRuleException.invalidPeriod(start, end);
        }
        long opening = entries.balanceBefore(customerId, start);
        long balance = opening;
        List<StatementLine> lines = new ArrayList<>();
        for (LoyaltyPointEntry entry : entries.findByCustomerIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
                customerId, start, end)) {
            balance += entry.getPoints();
            lines.add(StatementLine.of(entry.toView(), balance));
        }
        return new StatementView(customerId, start, end, opening, lines, balance);
    }

    private LoyaltyAccount lockedAccount(UUID customerId) {
        requireCustomer(customerId);
        return locks.account(customerId).orElseThrow(() -> LoyaltyRuleException.notMember(customerId));
    }

    private void requireCustomer(UUID customerId) {
        if (customers.findCustomer(customerId).isEmpty()) {
            throw new LoyaltyNotFoundException(CUSTOMER, customerId);
        }
    }

    private static String requireReason(String reason, String action) {
        if (reason == null || reason.isBlank()) {
            throw LoyaltyRuleException.reasonRequired(action);
        }
        return reason.strip();
    }

    private AccountView view(LoyaltyAccount account) {
        return new AccountView(account.getCustomerId(), account.getStatus().name(), account.getSuspendedReason(),
                account.getEnrolledAt(), entries.balance(account.getCustomerId()), account.getVersion());
    }
}
