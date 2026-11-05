package com.xavier.backend.reporting.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.reporting.exception.ReportStoreNotFoundException;
import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.model.dto.SalesRow;
import com.xavier.backend.reporting.model.dto.TaxRow;
import com.xavier.backend.reporting.model.dto.VoidsRow;
import com.xavier.backend.reporting.repository.ReportQueries;
import com.xavier.backend.reporting.repository.ReportQueries.CreditAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.ReceiptsAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.SalesAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.TaxAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.VoidsAggregate;
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

    /** Sales by day, hour, terminal, employee (who served the line), area, item, family or mode. */
    public ReportView<SalesRow> sales(UUID storeId, LocalDate from, LocalDate to, String groupBy) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        SalesGrouping grouping = SalesGrouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
        Currency currency = currencyOf(storeId);
        List<SalesAggregate> groups = queries.sales(grouping.key, grouping.label, grouping.needsHeader(), storeId,
                from, to);
        Map<String, String> names = switch (grouping) {
            case EMPLOYEE -> labels.employees(keys(groups));
            case TERMINAL -> labels.terminals(keys(groups));
            default -> Map.of();
        };
        List<SalesRow> rows = groups.stream().map(group -> salesRow(group, salesLabel(grouping, group, names),
                currency)).toList();
        SalesAggregate totals = salesTotals(storeId, from, to);
        return view(range, grouping.name(), rows, totals == null ? emptySales(currency) : salesRow(totals, null,
                currency), totals == null ? null : totals.asOf());
    }

    /** The {@code limit} best-selling items by quantity or by gross. */
    public ReportView<SalesRow> topItems(UUID storeId, LocalDate from, LocalDate to, String by, int limit) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        Currency currency = currencyOf(storeId);
        List<SalesRow> rows = queries.topItems(SalesGrouping.ITEM.key, SalesGrouping.ITEM.label, "gross".equals(by),
                limit, storeId, from, to).stream().map(group -> salesRow(group, group.label(), currency)).toList();
        SalesAggregate totals = salesTotals(storeId, from, to);
        return view(range, "item", rows, totals == null ? emptySales(currency) : salesRow(totals, null, currency),
                totals == null ? null : totals.asOf());
    }

    /** Net and tax per tax code and rate, from the documents' tax summaries. */
    public ReportView<TaxRow> taxes(UUID storeId, LocalDate from, LocalDate to) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        Currency currency = currencyOf(storeId);
        List<TaxAggregate> taxes = queries.taxes(storeId, from, to);
        List<TaxRow> rows = taxes.stream().map(tax -> new TaxRow(tax.taxCode(), tax.rate(),
                money(tax.net(), currency), money(tax.tax(), currency))).toList();
        TaxRow totals = new TaxRow(null, null,
                money(taxes.stream().map(TaxAggregate::net).reduce(BigDecimal.ZERO, BigDecimal::add), currency),
                money(taxes.stream().map(TaxAggregate::tax).reduce(BigDecimal.ZERO, BigDecimal::add), currency));
        return view(range, "rate", rows, totals,
                latest(taxes.stream().map(TaxAggregate::asOf).toArray(Instant[]::new)));
    }

    /** Voids by reason, employee (who voided), item or approver. */
    public ReportView<VoidsRow> voids(UUID storeId, LocalDate from, LocalDate to, String groupBy) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        VoidsGrouping grouping = VoidsGrouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
        Currency currency = currencyOf(storeId);
        List<VoidsAggregate> groups = queries.voids(grouping.key, grouping.label, storeId, from, to);
        Map<String, String> names = grouping == VoidsGrouping.EMPLOYEE || grouping == VoidsGrouping.APPROVER
                ? labels.employees(groups.stream().map(VoidsAggregate::key).toList()) : Map.of();
        List<VoidsRow> rows = groups.stream().map(group -> voidsRow(group,
                grouping == VoidsGrouping.ITEM ? group.label() : label(names, group.key()), currency)).toList();
        VoidsAggregate totals = queries.voids(ReportQueries.TOTAL, "NULL::text", storeId, from, to).stream()
                .findFirst().orElse(null);
        return view(range, grouping.name(), rows, totals == null
                        ? new VoidsRow(null, null, 0, BigDecimal.ZERO, money(null, currency), 0, 0)
                        : voidsRow(totals, null, currency), totals == null ? null : totals.asOf());
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

    private SalesAggregate salesTotals(UUID storeId, LocalDate from, LocalDate to) {
        return queries.sales(ReportQueries.TOTAL, "NULL::text", false, storeId, from, to).stream().findFirst()
                .orElse(null);
    }

    private static String salesLabel(SalesGrouping grouping, SalesAggregate group, Map<String, String> names) {
        if (group.key() == null) {
            return null;
        }
        return switch (grouping) {
            case HOUR -> group.key() + ":00";
            case ITEM -> group.label();
            default -> names.getOrDefault(group.key(), group.key());
        };
    }

    private static SalesRow salesRow(SalesAggregate group, String label, Currency currency) {
        return new SalesRow(group.key(), label, group.documents(), group.quantity(), money(group.gross(), currency),
                money(group.net(), currency), money(group.tax(), currency));
    }

    private static SalesRow emptySales(Currency currency) {
        return new SalesRow(null, null, 0, BigDecimal.ZERO, money(null, currency), money(null, currency),
                money(null, currency));
    }

    private static List<String> keys(List<SalesAggregate> groups) {
        return groups.stream().map(SalesAggregate::key).toList();
    }

    private static VoidsRow voidsRow(VoidsAggregate group, String label, Currency currency) {
        return new VoidsRow(group.key(), label, group.voids(), group.quantity(), money(group.gross(), currency),
                group.wasted(), group.afterDispatch());
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
