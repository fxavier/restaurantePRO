package com.xavier.backend.inventory.purchasing.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.dto.InventoryReport;
import com.xavier.backend.inventory.purchasing.model.dto.PurchaseRow;
import com.xavier.backend.inventory.purchasing.repository.PurchaseQueries;
import com.xavier.backend.inventory.service.InventoryScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Net purchases (received − returned + corrections) by supplier or by stock item over a period of business days
 * (D131), {@code INVENTORY_VIEW}; {@code totalValue} is the Σ of the rows' net values.
 */
@Service
public class PurchaseReportService {

    /** The longest report period, as inventory's other periodic reports (D85). */
    static final long MAX_DAYS = 366;

    /** How the report groups its rows. */
    public enum Grouping { SUPPLIER, ITEM }

    private final PurchaseQueries queries;
    private final InventoryScope scope;

    PurchaseReportService(PurchaseQueries queries, InventoryScope scope) {
        this.queries = queries;
        this.scope = scope;
    }

    /** @throws InventoryRuleException INVALID_PERIOD or RANGE_TOO_LARGE */
    @Transactional(readOnly = true)
    public InventoryReport<PurchaseRow> purchases(UUID storeId, LocalDate from, LocalDate to, Grouping grouping) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        if (from.isAfter(to)) {
            throw InventoryRuleException.invalidPeriod(from, to);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw InventoryRuleException.rangeTooLarge(MAX_DAYS);
        }
        List<PurchaseRow> rows = grouping == Grouping.SUPPLIER ? queries.bySupplier(storeId, from, to)
                : queries.byItem(storeId, from, to);
        return new InventoryReport<>(storeId, from, to, rows,
                rows.stream().map(PurchaseRow::netValue).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
