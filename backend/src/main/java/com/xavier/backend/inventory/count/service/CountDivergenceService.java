package com.xavier.backend.inventory.count.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.count.model.dto.DivergenceRow;
import com.xavier.backend.inventory.count.repository.CountLedgerQueries;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.dto.InventoryReport;
import com.xavier.backend.inventory.service.InventoryScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The count divergences of a store over a period of business days (§8.5 "divergências", D119): every approved line
 * that adjusted stock, with its value; the total is the net value the counts moved.
 */
@Service
public class CountDivergenceService {

    /** As the other inventory reports (D85, D131). */
    static final long MAX_DAYS = 366;

    private final CountLedgerQueries queries;
    private final InventoryScope scope;

    CountDivergenceService(CountLedgerQueries queries, InventoryScope scope) {
        this.queries = queries;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public InventoryReport<DivergenceRow> divergences(UUID storeId, LocalDate from, LocalDate to) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        if (from.isAfter(to)) {
            throw InventoryRuleException.invalidPeriod(from, to);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw InventoryRuleException.rangeTooLarge(MAX_DAYS);
        }
        List<DivergenceRow> rows = queries.divergences(storeId, from, to);
        BigDecimal total = rows.stream().map(DivergenceRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventoryReport<>(storeId, from, to, rows, total);
    }
}
