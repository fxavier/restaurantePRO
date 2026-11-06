package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.model.dto.BelowMinimumRow;
import com.xavier.backend.inventory.model.dto.InventoryReport;
import com.xavier.backend.inventory.model.dto.RotationRow;
import com.xavier.backend.inventory.model.dto.ValuationRow;
import com.xavier.backend.inventory.model.dto.WasteRow;
import com.xavier.backend.inventory.repository.InventoryQueries;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live stock reports of a store (D131), read from inventory's own facts: valuation (Σ balances), below minimum,
 * waste and rotation over business days. {@code INVENTORY_VIEW} in the store; a CSV export also needs
 * {@code REPORT_EXPORT} there and is audited {@code INVENTORY_REPORT_EXPORTED} (§10).
 */
@Service
public class InventoryReportService {

    private static final String LINE_END = "\r\n";

    private final InventoryQueries queries;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;

    InventoryReportService(InventoryQueries queries, InventoryScope scope, AccessPolicy accessPolicy,
                           AuditLog auditLog) {
        this.queries = queries;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public InventoryReport<ValuationRow> valuation(UUID storeId) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        return report(storeId, null, null, queries.valuation(storeId), ValuationRow::value);
    }

    @Transactional(readOnly = true)
    public InventoryReport<BelowMinimumRow> belowMinimum(UUID storeId) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        return report(storeId, null, null, queries.belowMinimum(storeId), BelowMinimumRow::value);
    }

    @Transactional(readOnly = true)
    public InventoryReport<WasteRow> waste(UUID storeId, LocalDate from, LocalDate to) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        Periods.requireReport(from, to);
        return report(storeId, from, to, queries.waste(storeId, from, to), WasteRow::value);
    }

    @Transactional(readOnly = true)
    public InventoryReport<RotationRow> rotation(UUID storeId, LocalDate from, LocalDate to) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        Periods.requireReport(from, to);
        return report(storeId, from, to, queries.rotation(storeId, from, to), RotationRow::consumptionCost);
    }

    /** The report as RFC 4180 CSV with a header line; audited with its parameters and row count. */
    @Transactional
    public String csv(String report, UUID storeId, LocalDate from, LocalDate to) {
        accessPolicy.require(Permission.REPORT_EXPORT, storeId);
        List<List<String>> lines = switch (report) {
            case "valuation" -> table(List.of("warehouseCode", "code", "name", "baseUnit", "quantity",
                    "averageCost", "value"), valuation(storeId).rows(), row -> List.of(row.warehouseCode(),
                    row.code(), row.name(), row.baseUnit(), plain(row.quantity()), plain(row.averageCost()),
                    plain(row.value())));
            case "below-minimum" -> table(List.of("warehouseCode", "code", "name", "baseUnit", "quantity",
                    "minQuantity", "missing"), belowMinimum(storeId).rows(), row -> List.of(row.warehouseCode(),
                    row.code(), row.name(), row.baseUnit(), plain(row.quantity()), plain(row.minQuantity()),
                    plain(row.missing())));
            case "waste" -> table(List.of("code", "name", "baseUnit", "quantity", "value"),
                    waste(storeId, from, to).rows(), row -> List.of(row.code(), row.name(), row.baseUnit(),
                            plain(row.quantity()), plain(row.value())));
            case "rotation" -> table(List.of("code", "name", "consumptionCost", "averageValue", "rotation"),
                    rotation(storeId, from, to).rows(), row -> List.of(row.code(), row.name(),
                            plain(row.consumptionCost()), plain(row.averageValue()), plain(row.rotation())));
            default -> throw new IllegalArgumentException("unknown report " + report);
        };
        Map<String, Object> details = new HashMap<>();
        details.put("report", report);
        details.put("rows", lines.size() - 1);
        if (from != null) {
            details.put("from", from.toString());
            details.put("to", to.toString());
        }
        auditLog.recordAsCurrentActor("INVENTORY_REPORT_EXPORTED", "InventoryReport",
                UuidVersion7Strategy.INSTANCE.generateUuid(null).toString(), storeId, null, null, details);
        return lines.stream().map(line -> line.stream().map(Csv::field)
                .collect(Collectors.joining(","))).collect(Collectors.joining(LINE_END, "", LINE_END));
    }

    private static <T> InventoryReport<T> report(UUID storeId, LocalDate from, LocalDate to, List<T> rows,
                                                 Function<T, BigDecimal> value) {
        BigDecimal total = rows.stream().map(value).reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
        return new InventoryReport<>(storeId, from, to, rows, total);
    }

    private static <T> List<List<String>> table(List<String> header, List<T> rows, Function<T, List<String>> line) {
        List<List<String>> lines = new ArrayList<>();
        lines.add(header);
        rows.forEach(row -> lines.add(line.apply(row)));
        return lines;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
