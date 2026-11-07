package com.xavier.backend.reporting.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.reporting.exception.ReportStoreNotFoundException;
import com.xavier.backend.reporting.model.dto.DiscountRow;
import com.xavier.backend.reporting.model.dto.MarginRow;
import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportStatus;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.model.dto.SalesRow;
import com.xavier.backend.reporting.model.dto.TaxRow;
import com.xavier.backend.reporting.model.dto.VoidsRow;
import com.xavier.backend.reporting.repository.RebuildRuns;
import com.xavier.backend.reporting.repository.ReportQueries;
import com.xavier.backend.reporting.repository.ReportQueries.CostAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.CreditAggregate;
import com.xavier.backend.reporting.repository.ReportQueries.DiscountAggregate;
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
    private final RebuildRuns runs;

    ReportService(AccessPolicy access, OrganizationApi organization, ReportQueries queries, Freshness freshness,
                  Labels labels, RebuildRuns runs) {
        this.runs = runs;
        this.access = access;
        this.organization = organization;
        this.queries = queries;
        this.freshness = freshness;
        this.labels = labels;
    }

    /** Pending facts, the newest projection and the last rebuild of the store (D86). */
    public ReportStatus status(UUID storeId) {
        access.require(Permission.REPORT_VIEW, storeId);
        currencyOf(storeId); // 404 for an unknown store
        return new ReportStatus(storeId, freshness.pendingFacts(), queries.lastProjectedAt(storeId),
                runs.last(storeId).orElse(null));
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

    /**
     * Margins by item, family or day (D131): net sales − cost of sales − cost variance, with waste apart. Both sides
     * are in the order's business day (D85); a cost variance is in the day it was recorded.
     */
    public ReportView<MarginRow> margins(UUID storeId, LocalDate from, LocalDate to, String groupBy) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        MarginGrouping grouping = MarginGrouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
        Currency currency = currencyOf(storeId);
        Map<String, Margin> groups = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        List<Instant> asOf = new java.util.ArrayList<>();
        add(groups, asOf, queries.margin(ReportQueries.MARGIN_SALES, grouping.salesKey, storeId, from, to),
                Margin::sales);
        add(groups, asOf, queries.margin(ReportQueries.MARGIN_COST, grouping.costKey, storeId, from, to),
                Margin::cost);
        add(groups, asOf, queries.margin(ReportQueries.MARGIN_WASTE, grouping.costKey, storeId, from, to),
                Margin::cost);
        add(groups, asOf, queries.margin(ReportQueries.MARGIN_VARIANCE, grouping.varianceKey, storeId, from, to),
                Margin::variance);
        Map<String, String> names = grouping == MarginGrouping.ITEM ? labels.items(groups.keySet()) : Map.of();
        List<MarginRow> rows = groups.entrySet().stream().map(group -> group.getValue().row(group.getKey(),
                grouping == MarginGrouping.FAMILY ? group.getKey() : label(names, group.getKey()), currency))
                .toList();
        return costView(range, grouping.name(), rows, marginTotals(rows, currency),
                latest(asOf.toArray(Instant[]::new)));
    }

    /** Discounts and offers by origin, promotion, employee (who gave it), approver or item, with the offers' cost. */
    public ReportView<DiscountRow> discounts(UUID storeId, LocalDate from, LocalDate to, String groupBy) {
        access.require(Permission.REPORT_VIEW, storeId);
        ReportRange range = new ReportRange(storeId, from, to);
        DiscountGrouping grouping = DiscountGrouping.valueOf(groupBy.toUpperCase(Locale.ROOT));
        Currency currency = currencyOf(storeId);
        List<DiscountAggregate> groups = queries.discounts(grouping.key, storeId, from, to);
        List<String> keys = groups.stream().map(DiscountAggregate::key).toList();
        Map<String, String> names = switch (grouping) {
            case EMPLOYEE, APPROVER -> labels.employees(keys);
            case ITEM -> labels.items(keys);
            default -> Map.of();
        };
        List<DiscountRow> rows = groups.stream().map(group -> discountRow(group,
                grouping == DiscountGrouping.ORIGIN || grouping == DiscountGrouping.PROMOTION ? group.key()
                        : label(names, group.key()), currency)).toList();
        return costView(range, grouping.name(), rows, discountTotals(rows, currency),
                latest(groups.stream().map(DiscountAggregate::asOf).toArray(Instant[]::new)));
    }

    /** The totals are the sum of the rows as shown, so an export's rows always add up to its TOTAL (S53 review). */
    private static MarginRow marginTotals(List<MarginRow> rows, Currency currency) {
        Money net = sum(rows.stream().map(MarginRow::netSales), currency);
        Money margin = sum(rows.stream().map(MarginRow::margin), currency);
        return new MarginRow(null, null, net, sum(rows.stream().map(MarginRow::costOfSales), currency),
                sum(rows.stream().map(MarginRow::waste), currency),
                sum(rows.stream().map(MarginRow::costVariance), currency), margin, percentOf(margin, net));
    }

    private static DiscountRow discountTotals(List<DiscountRow> rows, Currency currency) {
        return new DiscountRow(null, null, rows.stream().mapToLong(DiscountRow::lines).sum(),
                rows.stream().map(DiscountRow::quantity).reduce(BigDecimal.ZERO, BigDecimal::add),
                sum(rows.stream().map(DiscountRow::discount), currency),
                sum(rows.stream().map(DiscountRow::offerCost), currency));
    }

    private static Money sum(Stream<Money> amounts, Currency currency) {
        return amounts.reduce(money(null, currency), Money::plus);
    }

    private static BigDecimal percentOf(Money margin, Money net) {
        return net.amount().signum() == 0 ? null
                : margin.amount().multiply(HUNDRED).divide(net.amount(), 2, Money.ROUNDING);
    }

    private static void add(Map<String, Margin> groups, List<Instant> asOf, List<CostAggregate> rows,
                            java.util.function.BiConsumer<Margin, CostAggregate> into) {
        rows.forEach(row -> {
            into.accept(groups.computeIfAbsent(row.key(), key -> new Margin()), row);
            asOf.add(row.asOf());
        });
    }

    private static DiscountRow discountRow(DiscountAggregate group, String label, Currency currency) {
        return new DiscountRow(group.key(), label, group.lines(), group.quantity(),
                money(rounded(group.discount(), currency), currency),
                money(rounded(group.offerCost(), currency), currency));
    }

    /** The margin sums of one group, at the projections' scale until they are shown. */
    private static final class Margin {

        private BigDecimal netSales = BigDecimal.ZERO;
        private BigDecimal costOfSales = BigDecimal.ZERO;
        private BigDecimal waste = BigDecimal.ZERO;
        private BigDecimal variance = BigDecimal.ZERO;

        void sales(CostAggregate row) {
            netSales = netSales.add(row.amount());
        }

        void cost(CostAggregate row) {
            costOfSales = costOfSales.add(row.amount());
            waste = waste.add(row.waste());
        }

        void variance(CostAggregate row) {
            variance = variance.add(row.amount());
        }

        /** Rounded once for display (D113); the margin is computed from the shown amounts, so the row adds up. */
        MarginRow row(String key, String label, Currency currency) {
            Money net = money(rounded(netSales, currency), currency);
            Money cost = money(rounded(costOfSales, currency), currency);
            Money costVariance = money(rounded(variance, currency), currency);
            Money margin = net.minus(cost).minus(costVariance);
            return new MarginRow(key, label, net, cost, money(rounded(waste, currency), currency), costVariance,
                    margin, percentOf(margin, net));
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A projection amount (scale 4) at the minor unit: reports round only when they show money (D113). */
    private static BigDecimal rounded(BigDecimal amount, Currency currency) {
        return amount == null ? null : amount.setScale(currency.getDefaultFractionDigits(), Money.ROUNDING);
    }

    /** A cost report's view (D131): its freshness also waits on consumption, its finality on open exceptions. */
    private <T> ReportView<T> costView(ReportRange range, String groupBy, List<T> rows, T totals, Instant asOf) {
        long pending = freshness.pendingCostFacts();
        return new ReportView<>(range.storeId(), range.from(), range.to(), groupBy.toLowerCase(Locale.ROOT), rows,
                totals, asOf, pending, freshness.isCostFinal(range.storeId(), range.from(), range.to(), pending));
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
