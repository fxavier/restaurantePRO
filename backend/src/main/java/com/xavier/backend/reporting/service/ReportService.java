package com.xavier.backend.reporting.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.reporting.exception.ReportStoreNotFoundException;
import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.repository.ReportQueries;
import com.xavier.backend.reporting.repository.ReportQueries.CreditAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.ReceiptsAggregate;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The BackOffice reports (spec §5 "Relatórios", PRD S32): each needs {@code REPORT_VIEW} in the store, reads only the
 * projections over a business-date range (D85) and carries its freshness and finality (D86).
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final AccessPolicy access;
    private final OrganizationApi organization;
    private final ReportQueries queries;
    private final Freshness freshness;
    private final Labels labels;

    ReportService(AccessPolicy access, OrganizationApi organization, ReportQueries queries, Freshness freshness,
                  Labels labels) {
        this.access = access;
        this.organization = organization;
        this.queries = queries;
        this.freshness = freshness;
        this.labels = labels;
    }

    /** Receipts by method, employee or day; credit apart (D87). */
    public ReportView<ReceiptsRow> receipts(UUID storeId, LocalDate from, LocalDate to, String groupBy) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        ReceiptsGrouping grouping = ReceiptsGrouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
        Currency currency = currencyOf(storeId);
        Map<String, ReceiptsAggregate> money = byKey(queries.receipts(grouping.key, storeId, from, to),
                ReceiptsAggregate::key);
        Map<String, CreditAggregate> credit = grouping == ReceiptsGrouping.DAY
                ? byKey(queries.credit(grouping.key, storeId, from, to), CreditAggregate::key) : Map.of();
        Map<String, String> names = grouping == ReceiptsGrouping.EMPLOYEE ? labels.employees(money.keySet())
                : Map.of();
        List<ReceiptsRow> rows = Stream.concat(money.keySet().stream(), credit.keySet().stream()).distinct()
                .sorted(Comparator.nullsLast(Comparator.naturalOrder()))
                .map(key -> receiptsRow(key, label(names, key), money.get(key), credit.get(key), currency))
                .toList();
        ReceiptsAggregate totalMoney = queries.receipts(ReportQueries.TOTAL, storeId, from, to).stream()
                .findFirst().orElse(null);
        CreditAggregate totalCredit = queries.credit(ReportQueries.TOTAL, storeId, from, to).stream().findFirst()
                .orElse(null);
        Instant asOf = latest(totalMoney == null ? null : totalMoney.asOf(),
                totalCredit == null ? null : totalCredit.asOf());
        return view(range, grouping.name(), rows, receiptsRow(null, null, totalMoney, totalCredit, currency), asOf);
    }

    private <T> ReportView<T> view(ReportRange range, String groupBy, List<T> rows, T totals, Instant asOf) {
        long pending = freshness.pendingFacts();
        return new ReportView<>(range.storeId(), range.from(), range.to(), groupBy.toLowerCase(Locale.ROOT), rows,
                totals, asOf, pending, freshness.isFinal(range.storeId(), range.from(), range.to(), pending));
    }

    private static ReceiptsRow receiptsRow(String key, String label, ReceiptsAggregate money, CreditAggregate credit,
                                           Currency currency) {
        return new ReceiptsRow(key, label, money(money == null ? null : money.received(), currency),
                money(credit == null ? null : credit.onAccount(), currency),
                money(money == null ? null : money.debtCollected(), currency));
    }

    private Currency currencyOf(UUID storeId) {
        return organization.findStore(storeId).map(StoreSummary::currency)
                .orElseThrow(() -> new ReportStoreNotFoundException(storeId));
    }

    private static String label(Map<String, String> names, String key) {
        return key == null ? null : names.getOrDefault(key, key);
    }

    static Money money(BigDecimal amount, Currency currency) {
        return Money.of(amount == null ? BigDecimal.ZERO : amount, currency);
    }

    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        Map<String, T> byKey = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        rows.forEach(row -> byKey.put(key.apply(row), row));
        return byKey;
    }

    static Instant latest(Instant... instants) {
        return Stream.of(instants).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
