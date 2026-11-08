package com.xavier.backend.inventory.count.repository;

import com.xavier.backend.inventory.count.model.dto.DivergenceRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Whether {@code warehouseId} has a movement at or before {@code instant} that is not consumption: sales
     * ({@code ORDER_LINE}, {@code ORDER_LINE_COMPONENT}) and their void reversals ({@code VOID},
     * {@code VOID_COMPONENT}), with the revaluations they bring, happen live once the store is active and are absorbed by the opening (D122
     * amendment); a receipt, transfer, preparation, manual movement or count is a fact the opening must precede.
     */
    public boolean hasNonConsumptionMovementAtOrBefore(UUID warehouseId, Instant instant) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM inventory.stock_movement
                                       WHERE warehouse_id = ? AND occurred_at <= ?
                                         AND source_type NOT IN ('ORDER_LINE', 'ORDER_LINE_COMPONENT', 'VOID',
                                                                 'VOID_COMPONENT'))""")
                .params(warehouseId, Timestamp.from(instant)).query(Boolean.class).single();
    }

    /** The items with a non-zero balance in {@code warehouseId}, with their base unit, by item code. */
    public Map<UUID, String> onHand(UUID warehouseId) {
        Map<UUID, String> items = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT b.stock_item_id, i.base_unit FROM inventory.stock_balance b
                        JOIN inventory.stock_item i ON i.id = b.stock_item_id
                        WHERE b.warehouse_id = ? AND b.quantity <> 0
                        ORDER BY i.code""")
                .param(warehouseId)
                .query(rs -> {
                    items.put(rs.getObject("stock_item_id", UUID.class), rs.getString("base_unit"));
                });
        return items;
    }

    /**
     * The open consumption exceptions of {@code storeId} for a line confirmed at or before {@code cutOff}
     * ({@code confirmed_at}, S47), however late they were raised. Served by
     * {@code consumption_exception_open_confirmed_ix}.
     */
    public long openExceptions(UUID storeId, Instant cutOff) {
        return jdbc.sql("""
                        SELECT count(*) FROM inventory.consumption_exception
                        WHERE store_id = ? AND resolved_at IS NULL AND confirmed_at <= ?""")
                .params(storeId, Timestamp.from(cutOff)).query(Long.class).single();
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

    /**
     * Brings a scheduled go-live of {@code storeId} forward to {@code now} when it is still in the future (D122a
     * amendment: an opening started before it makes the store live at once).
     *
     * @return the scheduled instant it replaced, or empty when there was none in the future
     */
    public Optional<Instant> bringActivationForward(UUID storeId, Instant now) {
        return jdbc.sql("""
                        UPDATE inventory.inventory_activation a SET active_from = ?
                        FROM (SELECT store_id, active_from FROM inventory.inventory_activation
                              WHERE store_id = ? FOR UPDATE) old
                        WHERE a.store_id = old.store_id AND old.active_from > ?
                        RETURNING old.active_from""")
                .params(Timestamp.from(now), storeId, Timestamp.from(now))
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
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
