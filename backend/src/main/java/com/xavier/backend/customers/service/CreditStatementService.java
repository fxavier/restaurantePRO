package com.xavier.backend.customers.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.LedgerEntryView;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.customers.exception.CustomersNotFoundException;
import com.xavier.backend.customers.model.CreditAccount;
import com.xavier.backend.customers.model.CreditLedgerEntry;
import com.xavier.backend.customers.model.CreditSettlement;
import com.xavier.backend.customers.model.dto.DebtRow;
import com.xavier.backend.customers.model.dto.SettlementLink;
import com.xavier.backend.customers.model.dto.StatementLine;
import com.xavier.backend.customers.model.dto.StatementView;
import com.xavier.backend.customers.repository.CreditAccountRepository;
import com.xavier.backend.customers.repository.CreditLedgerEntryRepository;
import com.xavier.backend.customers.repository.CreditSettlementRepository;
import com.xavier.backend.customers.repository.CustomerRepository;
import com.xavier.backend.customers.repository.DebtAging;
import com.xavier.backend.customers.repository.EntryAmount;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statements and debts by age ("extratos", "dívidas", spec §5, §8.6; D73), read from the ledger: nothing here is
 * stored. {@code CUSTOMER_VIEW} installation-wide or in any store, because customers are company-wide (P38).
 */
@Service
public class CreditStatementService {

    private final CustomerRepository customers;
    private final CreditAccountRepository accounts;
    private final CreditLedgerEntryRepository entries;
    private final CreditSettlementRepository settlements;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    CreditStatementService(CustomerRepository customers, CreditAccountRepository accounts,
                           CreditLedgerEntryRepository entries, CreditSettlementRepository settlements,
                           OrganizationApi organization, AccessPolicy accessPolicy, Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.entries = entries;
        this.settlements = settlements;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /** Every entry in {@code [from, to]} (default: since the start, until now) with its running balance. */
    @Transactional(readOnly = true)
    public StatementView statement(UUID customerId, Instant from, Instant to) {
        accessPolicy.requireInAnyStore(Permission.CUSTOMER_VIEW);
        if (!customers.existsById(customerId)) {
            throw new CustomersNotFoundException(CustomerService.ENTITY, customerId);
        }
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? clock.instant() : to;
        if (start.isAfter(end)) {
            throw CustomerRuleException.invalidPeriod(start, end);
        }
        Currency currency = accounts.findById(customerId).map(CreditAccount::currency)
                .orElseGet(() -> organization.companyProfile().currency());
        Money opening = Money.of(entries.balanceBefore(customerId, start), currency);
        List<LedgerEntryView> found = entries
                .findByCustomerIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(customerId, start, end).stream()
                .map(CreditLedgerEntry::toView).toList();
        List<UUID> ids = found.stream().map(LedgerEntryView::id).toList();
        Map<UUID, List<SettlementLink>> links = links(ids, currency);
        Map<UUID, BigDecimal> open = ids.isEmpty() ? Map.of() : entries.openAmountsOf(customerId, ids).stream()
                .collect(Collectors.toMap(EntryAmount::getEntryId, EntryAmount::getAmount));
        List<StatementLine> lines = new ArrayList<>();
        Money balance = opening;
        for (LedgerEntryView entry : found) {
            balance = "DEBIT".equals(entry.kind()) ? balance.plus(entry.amount()) : balance.minus(entry.amount());
            BigDecimal stillOpen = open.get(entry.id());
            lines.add(new StatementLine(entry.id(), entry.kind(), entry.sourceType(), entry.sourceId(),
                    entry.documentId(), entry.storeId(), entry.businessDate(), entry.description(),
                    entry.occurredAt(), entry.amount(), balance,
                    stillOpen == null ? null : Money.of(stillOpen, currency),
                    links.getOrDefault(entry.id(), List.of())));
        }
        return new StatementView(customerId, start, end, opening, lines, balance);
    }

    /** Customers owing at least {@code minBalance} as of {@code asOf} (default now), by name. */
    @Transactional(readOnly = true)
    public PageResponse<DebtRow> debts(Instant asOf, BigDecimal minBalance, Pageable pageable) {
        accessPolicy.requireInAnyStore(Permission.CUSTOMER_VIEW);
        return PageResponse.from(entries.debts(asOf == null ? clock.instant() : asOf,
                        minBalance == null ? BigDecimal.ZERO : minBalance,
                        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(CreditStatementService::row));
    }

    private Map<UUID, List<SettlementLink>> links(List<UUID> ids, Currency currency) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<SettlementLink>> links = new HashMap<>();
        for (CreditSettlement s : settlements.involving(ids)) {
            SettlementLink link = new SettlementLink(s.getCreditEntryId(), s.getDebitEntryId(),
                    s.getReversalEntryId(), Money.of(s.getAmount(), currency));
            Stream.of(s.getCreditEntryId(), s.getDebitEntryId(), s.getReversalEntryId()).filter(Objects::nonNull)
                    .filter(ids::contains)
                    .forEach(id -> links.computeIfAbsent(id, k -> new ArrayList<>()).add(link));
        }
        return links;
    }

    private static DebtRow row(DebtAging aging) {
        Currency currency = Currency.getInstance(aging.getCurrency());
        return new DebtRow(aging.getCustomerId(), aging.getName(), aging.getTaxId(),
                Money.of(aging.getBalance(), currency), Money.of(aging.getDays0To30(), currency),
                Money.of(aging.getDays31To60(), currency), Money.of(aging.getDays61To90(), currency),
                Money.of(aging.getOver90(), currency));
    }
}
