package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.dto.BelowMinimumRow;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.RotationRow;
import com.xavier.backend.inventory.model.dto.StockRow;
import com.xavier.backend.inventory.model.dto.ValuationRow;
import com.xavier.backend.inventory.model.dto.WasteRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The read side of inventory: stock and movement listings (D23 pages), status counts and the D131 reports. */
@Repository
public class InventoryQueries {

    /** The items a warehouse holds or has a minimum for. */
    static final String STOCK = """
            FROM (SELECT stock_item_id FROM inventory.stock_balance WHERE warehouse_id = :warehouse
                  UNION SELECT stock_item_id FROM inventory.stock_level WHERE warehouse_id = :warehouse) k
            JOIN inventory.stock_item i ON i.id = k.stock_item_id
            LEFT JOIN inventory.stock_balance b ON b.warehouse_id = :warehouse AND b.stock_item_id = k.stock_item_id
            LEFT JOIN inventory.stock_level l ON l.warehouse_id = :warehouse AND l.stock_item_id = k.stock_item_id
            WHERE (NOT :belowMinimum OR coalesce(b.quantity, 0) < l.min_quantity)
              AND (NOT :negative OR b.quantity < 0)""";

    private static final String MOVEMENTS = """
            FROM inventory.stock_movement
            WHERE warehouse_id = :warehouse
              AND (CAST(:item AS uuid) IS NULL OR stock_item_id = :item)
              AND (CAST(:from AS date) IS NULL OR business_date >= :from)
              AND (CAST(:to AS date) IS NULL OR business_date <= :to)
              AND (CAST(:type AS text) IS NULL OR type = :type)""";

    private final JdbcClient jdbc;

    InventoryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<StockRow> stock(UUID warehouseId, boolean belowMinimum, boolean negative, Pageable pageable) {
        List<StockRow> rows = jdbc.sql("""
                        SELECT i.id, i.code, i.name, i.base_unit, coalesce(b.quantity, 0) AS quantity,
                               coalesce(b.value, 0) AS value, coalesce(b.average_cost, 0) AS average_cost,
                               l.min_quantity
                        %s ORDER BY i.code, i.id LIMIT :limit OFFSET :offset""".formatted(STOCK))
                .param("warehouse", warehouseId).param("belowMinimum", belowMinimum).param("negative", negative)
                .param("limit", pageable.getPageSize()).param("offset", pageable.getOffset())
                .query((rs, n) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal min = rs.getBigDecimal("min_quantity");
                    return new StockRow(warehouseId, rs.getObject("id", UUID.class), rs.getString("code"),
                            rs.getString("name"), rs.getString("base_unit"), quantity, rs.getBigDecimal("value"),
                            rs.getBigDecimal("average_cost"), min, min != null && quantity.compareTo(min) < 0);
                }).list();
        long total = jdbc.sql("SELECT count(*) " + STOCK)
                .param("warehouse", warehouseId).param("belowMinimum", belowMinimum).param("negative", negative)
                .query(Long.class).single();
        return new PageImpl<>(rows, pageable, total);
    }

    /** Newest first. */
    public Page<MovementView> movements(UUID warehouseId, UUID stockItemId, LocalDate from, LocalDate to,
                                        String type, Pageable pageable) {
        List<MovementView> rows = jdbc.sql("SELECT " + LedgerStore.MOVEMENT_COLUMNS + " " + MOVEMENTS
                        + " ORDER BY posting_seq DESC LIMIT :limit OFFSET :offset")
                .param("warehouse", warehouseId).param("item", stockItemId).param("from", from).param("to", to)
                .param("type", type).param("limit", pageable.getPageSize()).param("offset", pageable.getOffset())
                .query(LedgerStore::movement).list();
        long total = jdbc.sql("SELECT count(*) " + MOVEMENTS)
                .param("warehouse", warehouseId).param("item", stockItemId).param("from", from).param("to", to)
                .param("type", type).query(Long.class).single();
        return new PageImpl<>(rows, pageable, total);
    }

    public long openExceptions(UUID storeId) {
        return jdbc.sql("SELECT count(*) FROM inventory.consumption_exception WHERE store_id = ? AND resolved_at IS NULL")
                .param(storeId).query(Long.class).single();
    }

    /** The negative balances of a store's warehouses (D118), served by {@code stock_balance_negative_ix}. */
    static final String NEGATIVE_BALANCES = """
            SELECT count(*) FROM inventory.stock_balance b JOIN inventory.warehouse w ON w.id = b.warehouse_id
            WHERE w.store_id = ? AND b.quantity < 0""";

    public long negativeBalances(UUID storeId) {
        return jdbc.sql(NEGATIVE_BALANCES).param(storeId).query(Long.class).single();
    }

    /** Σ balances of the store's warehouses (A52: stock valuation = Σ balances). */
    static final String VALUATION = """
            SELECT w.id AS warehouse_id, w.code AS warehouse_code, i.id, i.code, i.name, i.base_unit,
                   b.quantity, b.average_cost, b.value
            FROM inventory.stock_balance b
            JOIN inventory.warehouse w ON w.id = b.warehouse_id
            JOIN inventory.stock_item i ON i.id = b.stock_item_id
            WHERE w.store_id = ? AND (b.quantity <> 0 OR b.value <> 0)
            ORDER BY w.code, i.code""";

    /** The wasted stock of a store over a period: WASTE movements and wasted voids (D117 as amended). */
    static final String WASTE = """
            SELECT i.id, i.code, i.name, i.base_unit, sum(w.quantity) AS quantity, sum(w.value) AS value
            FROM (SELECT m.stock_item_id, -m.quantity AS quantity, -m.value AS value
                  FROM inventory.stock_movement m
                  WHERE m.store_id = ? AND m.business_date BETWEEN ? AND ? AND m.type = 'WASTE'
                  UNION ALL
                  -- wasted voids (D117 as amended): no stock movement, the sale's cost reclassified
                  SELECT r.stock_item_id, r.quantity, r.value FROM inventory.waste_record r
                  WHERE r.store_id = ? AND r.business_date BETWEEN ? AND ?) w
            JOIN inventory.stock_item i ON i.id = w.stock_item_id
            GROUP BY i.id, i.code, i.name, i.base_unit ORDER BY i.code""";

    public List<ValuationRow> valuation(UUID storeId) {
        return jdbc.sql(VALUATION)
                .param(storeId)
                .query((rs, n) -> new ValuationRow(rs.getObject("warehouse_id", UUID.class),
                        rs.getString("warehouse_code"), rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("base_unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("average_cost"), rs.getBigDecimal("value"))).list();
    }

    public List<BelowMinimumRow> belowMinimum(UUID storeId) {
        return jdbc.sql("""
                        SELECT w.id AS warehouse_id, w.code AS warehouse_code, i.id, i.code, i.name, i.base_unit,
                               coalesce(b.quantity, 0) AS quantity, l.min_quantity, coalesce(b.value, 0) AS value
                        FROM inventory.stock_level l
                        JOIN inventory.warehouse w ON w.id = l.warehouse_id
                        JOIN inventory.stock_item i ON i.id = l.stock_item_id
                        LEFT JOIN inventory.stock_balance b
                          ON b.warehouse_id = l.warehouse_id AND b.stock_item_id = l.stock_item_id
                        WHERE w.store_id = ? AND coalesce(b.quantity, 0) < l.min_quantity
                        ORDER BY w.code, i.code""")
                .param(storeId)
                .query((rs, n) -> new BelowMinimumRow(rs.getObject("warehouse_id", UUID.class),
                        rs.getString("warehouse_code"), rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("base_unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("min_quantity"),
                        rs.getBigDecimal("min_quantity").subtract(rs.getBigDecimal("quantity")),
                        rs.getBigDecimal("value"))).list();
    }

    public List<WasteRow> waste(UUID storeId, LocalDate from, LocalDate to) {
        return jdbc.sql(WASTE)
                .params(storeId, from, to, storeId, from, to)
                .query((rs, n) -> new WasteRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("base_unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("value"))).list();
    }

    public List<RotationRow> rotation(UUID storeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT i.id, i.code, i.name,
                               -coalesce(sum(m.value) FILTER (WHERE m.business_date BETWEEN :from AND :to
                                   AND m.type IN ('SALE_CONSUMPTION', 'CONSUMPTION_REVERSAL')), 0)
                               -- a wasted void moves its share of the sale's cost to waste (D117 as amended)
                               - coalesce((SELECT sum(r.value) FROM inventory.waste_record r
                                           WHERE r.store_id = :store AND r.stock_item_id = i.id
                                             AND r.business_date BETWEEN :from AND :to), 0) AS consumption,
                               coalesce(sum(m.value) FILTER (WHERE m.business_date < :from), 0) AS opening,
                               coalesce(sum(m.value) FILTER (WHERE m.business_date <= :to), 0) AS closing
                        FROM inventory.stock_movement m JOIN inventory.stock_item i ON i.id = m.stock_item_id
                        WHERE m.store_id = :store AND m.business_date <= :to
                        GROUP BY i.id, i.code, i.name
                        HAVING bool_or(m.business_date BETWEEN :from AND :to)
                        ORDER BY i.code""")
                .param("store", storeId).param("from", from).param("to", to)
                .query((rs, n) -> {
                    BigDecimal consumption = rs.getBigDecimal("consumption");
                    BigDecimal opening = rs.getBigDecimal("opening");
                    BigDecimal closing = rs.getBigDecimal("closing");
                    BigDecimal average = opening.add(closing).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
                    BigDecimal rotation = average.signum() > 0
                            ? consumption.divide(average, 4, RoundingMode.HALF_UP) : null;
                    return new RotationRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            consumption, opening, closing, average, rotation, consumption);
                }).list();
    }
}
