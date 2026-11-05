package com.xavier.backend.customers.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.customers.CreditView;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.model.CreditAccount;
import com.xavier.backend.customers.model.CreditLimitChange;
import com.xavier.backend.customers.model.dto.CreditBlockRequest;
import com.xavier.backend.customers.model.dto.CreditLimitChangeRequest;
import com.xavier.backend.customers.model.dto.CreditUnblockRequest;
import com.xavier.backend.customers.repository.CreditAccountRepository;
import com.xavier.backend.customers.repository.CreditLimitChangeRepository;
import com.xavier.backend.customers.repository.CustomerRepository;
import com.xavier.backend.customers.exception.CustomersNotFoundException;
import com.xavier.backend.customers.exception.CustomersConflictException;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit account's limit and block (D73, spec §9). {@code CREDIT_LIMIT_MANAGE} installation-wide, because the
 * limit is company-wide (P38); every change is audited, and a limit change is also a {@code CreditLimitChange} fact.
 * The account is locked first, so a change and a reservation never interleave (D76).
 */
@Service
public class CreditAccountService {

    static final String ENTITY = "CustomerCreditAccount";

    private final CustomerRepository customers;
    private final CreditAccountRepository accounts;
    private final CreditLimitChangeRepository limitChanges;
    private final CreditLocks locks;
    private final CreditBalances balances;
    private final CreditLedgerService ledger;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    CreditAccountService(CustomerRepository customers, CreditAccountRepository accounts,
                         CreditLimitChangeRepository limitChanges, CreditLocks locks, CreditBalances balances,
                         CreditLedgerService ledger, OrganizationApi organization, AccessPolicy accessPolicy,
                         AuditLog auditLog, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.limitChanges = limitChanges;
        this.locks = locks;
        this.balances = balances;
        this.ledger = ledger;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Sets the limit; the first change opens the account, in the company currency. */
    @Transactional
    public CreditView changeLimit(UUID customerId, CreditLimitChangeRequest request) {
        accessPolicy.require(Permission.CREDIT_LIMIT_MANAGE);
        requireCustomer(customerId);
        Instant now = clock.instant();
        CreditAccount account = locks.account(customerId).orElse(null);
        BigDecimal oldLimit = account == null ? null : account.limit().amount();
        if (account == null) {
            account = open(customerId, now);
        }
        Money newLimit = money(request.newLimit(), account);
        account.changeLimit(newLimit);
        accounts.saveAndFlush(account);
        Actor actor = accessPolicy.current();
        limitChanges.save(CreditLimitChange.of(customerId, oldLimit, newLimit.amount(), request.reason(),
                actor.kind().name(), actor.userId(), now));
        Map<String, Object> details = new HashMap<>();
        details.put("oldLimit", oldLimit == null ? null : oldLimit.toPlainString());
        details.put("newLimit", newLimit.amount().toPlainString());
        auditLog.recordAsCurrentActor("CREDIT_LIMIT_CHANGED", ENTITY, customerId.toString(), null, null,
                request.reason().strip(), details);
        return balances.view(account);
    }

    @Transactional
    public CreditView block(UUID customerId, CreditBlockRequest request) {
        accessPolicy.require(Permission.CREDIT_LIMIT_MANAGE);
        CreditAccount account = requireAccount(customerId);
        account.block(request.reason(), clock.instant());
        accounts.saveAndFlush(account);
        auditLog.recordAsCurrentActor("CREDIT_BLOCKED", ENTITY, customerId.toString(), null, null,
                account.getBlockedReason(), Map.of());
        return balances.view(account);
    }

    @Transactional
    public CreditView unblock(UUID customerId, CreditUnblockRequest request) {
        accessPolicy.require(Permission.CREDIT_LIMIT_MANAGE);
        CreditAccount account = requireAccount(customerId);
        account.unblock();
        accounts.saveAndFlush(account);
        auditLog.recordAsCurrentActor("CREDIT_UNBLOCKED", ENTITY, customerId.toString(), null, null,
                request.reason() == null || request.reason().isBlank() ? null : request.reason().strip(), Map.of());
        return balances.view(account);
    }

    @Transactional(readOnly = true)
    public CreditView credit(Channel channel, UUID customerId) {
        channel.authorize(accessPolicy, Permission.CUSTOMER_VIEW);
        return ledger.credit(customerId);
    }

    private CreditAccount requireAccount(UUID customerId) {
        requireCustomer(customerId);
        return locks.account(customerId).orElseThrow(() -> CustomerRuleException.accountMissing(customerId));
    }

    private void requireCustomer(UUID customerId) {
        if (!customers.existsById(customerId)) {
            throw new CustomersNotFoundException(CustomerService.ENTITY, customerId);
        }
    }

    /** Two first changes at once both try to open the account: the loser gets 409 and retries. */
    private CreditAccount open(UUID customerId, Instant now) {
        return UniqueConstraints.saveAndFlush(accounts,
                CreditAccount.open(customerId, organization.companyProfile().currency(), now),
                () -> CustomersConflictException.accountExists(customerId),
                "credit_account_pk");
    }

    private static Money money(BigDecimal amount, CreditAccount account) {
        try {
            return Money.of(amount, account.currency());
        } catch (IllegalArgumentException e) {
            throw CustomerRuleException.invalidAmount("The amount " + amount.toPlainString()
                    + " has more decimals than " + account.currency().getCurrencyCode() + " allows.");
        }
    }
}
