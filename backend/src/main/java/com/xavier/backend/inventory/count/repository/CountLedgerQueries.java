package com.xavier.backend.inventory.count.repository;

import com.xavier.backend.inventory.count.model.dto.DivergenceRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What a count reads from the ledger, in plain SQL: quantities at a cut-off, and the activation it may create. */
@Repository
public class CountLedgerQueries {

    public static final String SOURCE_TYPE = "STOCK_COUNT";

    private final JdbcClient jdbc;

    CountLedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Per item, Σ quantity of its movements in {@code warehouseId} with {@code occurred_at ≤} its cut-off (D119). */
    public Map<UUID, BigDecimal> quantitiesAt(UUID warehouseId, Map<UUID, Instant> cutOffByItem) {
        String values = cutOffByItem.keySet().stream().map(item -> "(?::uuid, ?::timestamptz)")
                .collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        cutOffByItem.forEach((item, cutOff) -> {
            params.add(item);
            params.add(Timestamp.from(cutOff));
        });
        params.add(warehouseId);
        Map<UUID, BigDecimal> quantities = new HashMap<>();
        jdbc.sql("""
                        SELECT c.stock_item_id, coalesce(sum(m.quantity), 0) AS quantity
                        FROM (VALUES %s) c (stock_item_id, cut_off)
                        LEFT JOIN inventory.stock_movement m
                          ON m.stock_item_id = c.stock_item_id AND m.warehouse_id = ? AND m.occurred_at <= c.cut_off
                        GROUP BY c.stock_item_id""".formatted(values))
                .params(params)
                .query(rs -> {
                    quantities.put(rs.getObject("stock_item_id", UUID.class), rs.getBigDecimal("quantity"));
                });
        return quantities;
    }

    /** Whether {@code warehouseId} has any movement that occurred at or before {@code instant}. */
    public boolean hasMovementAtOrBefore(UUID warehouseId, Instant instant) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM inventory.stock_movement
                                       WHERE warehouse_id = ? AND occurred_at <= ?)""")
                .params(warehouseId, Timestamp.from(instant)).query(Boolean.class).single();
    }

    /**
     * Activates inventory in {@code storeId} from {@code activeFrom} unless it is active already (D122: the opening
     * count is what makes a store live). Two openings racing in one store both succeed; only one activates.
     *
     * @return whether this call activated the store
     */
    public boolean activateIfAbsent(UUID storeId, Instant activeFrom, UUID accountId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO inventory.inventory_activation (store_id, active_from, activated_by_account_id,
                            activated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (store_id) DO NOTHING""")
                .params(storeId, Timestamp.from(activeFrom), accountId, Timestamp.from(now)).update() == 1;
    }

    /** The approved count lines of {@code storeId} that posted a COUNT_ADJUSTMENT on a business day in the period. */
    public List<DivergenceRow> divergences(UUID storeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT l.count_id, m.warehouse_id, l.stock_item_id, i.code, i.name, i.base_unit, l.counted_at,
                               m.business_date, l.expected_quantity, l.counted_quantity, l.difference, m.value
                        FROM inventory.stock_movement m
                        JOIN inventory.stock_count_line l ON l.id = m.source_line_id
                        JOIN inventory.stock_item i ON i.id = l.stock_item_id
                        WHERE m.store_id = ? AND m.business_date BETWEEN ? AND ? AND m.type = 'COUNT_ADJUSTMENT'
                          AND m.source_type = ?
                        ORDER BY m.business_date, l.counted_at, i.code""")
                .params(storeId, from, to, SOURCE_TYPE)
                .query((rs, n) -> new DivergenceRow(rs.getObject("count_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("stock_item_id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getString("base_unit"),
                        rs.getTimestamp("counted_at").toInstant(), rs.getObject("business_date", LocalDate.class),
                        rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("counted_quantity"),
                        rs.getBigDecimal("difference"), rs.getBigDecimal("value")))
                .list();
    }
}
