package com.xavier.backend.reporting.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.reporting.exception.ExportParamsException;
import com.xavier.backend.reporting.model.dto.DiscountRow;
import com.xavier.backend.reporting.model.dto.ExportRequest;
import com.xavier.backend.reporting.model.dto.MarginRow;
import com.xavier.backend.reporting.model.dto.ReceiptsRow;
import com.xavier.backend.reporting.model.dto.ReportView;
import com.xavier.backend.reporting.model.dto.SalesRow;
import com.xavier.backend.reporting.model.dto.TaxRow;
import com.xavier.backend.reporting.model.dto.VoidsRow;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.ops.ExportMonitor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A report as a CSV file (D86, P37): the same rows as its {@code GET} (with labels), then a {@code TOTAL} row. It
 * needs {@code REPORT_EXPORT} in the store, and {@code REPORT_VIEW} as the report itself does; each export is audited
 * {@code REPORT_EXPORTED} with its parameters and row count, committed with the export (§10). Every export is counted
 * by {@link ExportMonitor} (kind {@code report_csv}, D150).
 */
@Service
public class ReportExportService {

    static final String EXPORTED = "REPORT_EXPORTED";
    private static final String LINE_END = "\r\n";
    private static final Set<String> GROUPED = Set.of("sales", "receipts", "voids", "margins", "discounts");

    private final AccessPolicy access;
    private final ReportService reports;
    private final AuditLog auditLog;
    private final ExportMonitor exportMonitor;

    ReportExportService(AccessPolicy access, ReportService reports, AuditLog auditLog, ExportMonitor exportMonitor) {
        this.access = access;
        this.reports = reports;
        this.auditLog = auditLog;
        this.exportMonitor = exportMonitor;
    }

    @Transactional
    public ReportFile export(ExportRequest request) {
        return exportMonitor.monitor(ExportMonitor.REPORT_CSV, () -> csv(request));
    }

    private ReportFile csv(ExportRequest request) {
        Map<String, String> params = request.params();
        UUID storeId = parse(params, "storeId", UUID::fromString);
        access.require(Permission.REPORT_EXPORT, storeId);
        LocalDate from = parse(params, "from", LocalDate::parse);
        LocalDate to = parse(params, "to", LocalDate::parse);
        List<List<String>> lines = switch (request.report()) {
            case "sales" -> sales(reports.sales(storeId, from, to, grouping(params, SalesGrouping.class)));
            case "top-items" -> sales(reports.topItems(storeId, from, to, by(params),
                    parse(params, "limit", "20", ReportExportService::limit)));
            case "taxes" -> taxes(reports.taxes(storeId, from, to));
            case "receipts" -> receipts(reports.receipts(storeId, from, to,
                    grouping(params, ReceiptsGrouping.class)));
            case "voids" -> voids(reports.voids(storeId, from, to, grouping(params, VoidsGrouping.class)));
            case "margins" -> margins(reports.margins(storeId, from, to, grouping(params, MarginGrouping.class)));
            case "discounts" -> discounts(reports.discounts(storeId, from, to,
                    grouping(params, DiscountGrouping.class)));
            default -> throw new ExportParamsException("report");
        };
        int rows = lines.size() - 2; // the header and the totals
        auditLog.recordAsCurrentActor(EXPORTED, "Report", UuidVersion7Strategy.INSTANCE.generateUuid(null)
                .toString(), storeId, null, null, details(request, rows));
        String csv = lines.stream().map(line -> line.stream().map(ReportExportService::field)
                .collect(Collectors.joining(","))).collect(Collectors.joining(LINE_END, "", LINE_END));
        return new ReportFile(request.report() + "-" + from + "-" + to + ".csv", csv);
    }

    private static List<List<String>> sales(ReportView<SalesRow> view) {
        return table(List.of("key", "label", "documents", "quantity", "gross", "net", "tax"), view,
                row -> List.of(text(row.key()), text(row.label()), String.valueOf(row.documents()),
                        row.quantity().toPlainString(), amount(row.gross()), amount(row.net()), amount(row.tax())));
    }

    private static List<List<String>> taxes(ReportView<TaxRow> view) {
        return table(List.of("taxCode", "rate", "net", "tax"), view,
                row -> List.of(text(row.taxCode()), row.rate() == null ? "" : row.rate().toPlainString(),
                        amount(row.net()), amount(row.tax())));
    }

    private static List<List<String>> receipts(ReportView<ReceiptsRow> view) {
        return table(List.of("key", "label", "received", "onAccount", "debtCollected"), view,
                row -> List.of(text(row.key()), text(row.label()), amount(row.received()),
                        amount(row.onAccount()), amount(row.debtCollected())));
    }

    private static List<List<String>> voids(ReportView<VoidsRow> view) {
        return table(List.of("key", "label", "voids", "quantity", "gross", "wasted", "afterDispatch"), view,
                row -> List.of(text(row.key()), text(row.label()), String.valueOf(row.voids()),
                        row.quantity().toPlainString(), amount(row.gross()), String.valueOf(row.wasted()),
                        String.valueOf(row.afterDispatch())));
    }

    private static List<List<String>> margins(ReportView<MarginRow> view) {
        return table(List.of("key", "label", "netSales", "costOfSales", "waste", "costVariance", "margin",
                "marginPercent"), view, row -> List.of(text(row.key()), text(row.label()), amount(row.netSales()),
                amount(row.costOfSales()), amount(row.waste()), amount(row.costVariance()), amount(row.margin()),
                row.marginPercent() == null ? "" : row.marginPercent().toPlainString()));
    }

    private static List<List<String>> discounts(ReportView<DiscountRow> view) {
        return table(List.of("key", "label", "lines", "quantity", "discount", "offerCost"), view,
                row -> List.of(text(row.key()), text(row.label()), String.valueOf(row.lines()),
                        row.quantity().toPlainString(), amount(row.discount()), amount(row.offerCost())));
    }

    /** The header, one line per row, and the totals with {@code TOTAL} in the first column. */
    private static <T> List<List<String>> table(List<String> header, ReportView<T> view,
                                                Function<T, List<String>> line) {
        List<List<String>> lines = new ArrayList<>();
        lines.add(header);
        view.rows().forEach(row -> lines.add(line.apply(row)));
        List<String> totals = new ArrayList<>(line.apply(view.totals()));
        totals.set(0, "TOTAL");
        lines.add(totals);
        return lines;
    }

    private static <E extends Enum<E>> String grouping(Map<String, String> params, Class<E> groupings) {
        String groupBy = params.get("groupBy");
        if (groupBy == null || Stream.of(groupings.getEnumConstants())
                .noneMatch(grouping -> grouping.name().equalsIgnoreCase(groupBy))) {
            throw new ExportParamsException("groupBy");
        }
        return groupBy;
    }

    private static String by(Map<String, String> params) {
        String by = params.getOrDefault("by", "quantity");
        if (!Set.of("quantity", "gross").contains(by)) {
            throw new ExportParamsException("by");
        }
        return by;
    }

    private static int limit(String value) {
        int limit = Integer.parseInt(value);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit out of range");
        }
        return limit;
    }

    private static <T> T parse(Map<String, String> params, String name, Function<String, T> parser) {
        return parse(params, name, null, parser);
    }

    private static <T> T parse(Map<String, String> params, String name, String fallback,
                               Function<String, T> parser) {
        String value = params.getOrDefault(name, fallback);
        if (value == null) {
            throw new ExportParamsException(name);
        }
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            throw new ExportParamsException(name);
        }
    }

    private static Map<String, Object> details(ExportRequest request, int rows) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("report", request.report());
        request.params().entrySet().stream()
                .filter(param -> Set.of("storeId", "from", "to", "groupBy", "by", "limit").contains(param.getKey()))
                .forEach(param -> details.put(param.getKey(), param.getValue()));
        details.put("rows", rows);
        return details;
    }

    private static String amount(Money money) {
        return money.amount().toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** RFC 4180 quoting; a field a spreadsheet would run as a formula is defused (CSV injection). */
    static String field(String text) {
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !isNumber(text)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** A negative amount (a refund) is data, not a formula. */
    private static boolean isNumber(String text) {
        return text.matches("-?\\d+(\\.\\d+)?");
    }
}
