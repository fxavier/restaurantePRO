package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.CostVarianceFact;
import com.xavier.backend.inventory.MovementFact;
import com.xavier.backend.inventory.SourceRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reads behind {@code InventoryFacts}: movements by source (the source key's index) and sources by day; waste
 * records and cost variances by source and by day (their {@code (store_id, business_date)} indexes).
 */
@Repository
public class FactQueries {

    private final JdbcClient jdbc;

    FactQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MovementFact> movementsOf(String sourceType, UUID sourceId) {
        return jdbc.sql("""
                        SELECT id, posting_seq, store_id, warehouse_id, stock_item_id, type, quantity, unit_cost, value,
                               currency, source_line_id, reverses_movement_id, sale_item_id, recipe_version_id,
                               business_date, occurred_at
                        FROM inventory.stock_movement WHERE source_type = ? AND source_id = ? ORDER BY posting_seq""")
                .params(sourceType, sourceId)
                .query((rs, n) -> new MovementFact(rs.getObject("id", UUID.class), rs.getLong("posting_seq"),
                        rs.getObject("store_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("stock_item_id", UUID.class), rs.getString("type"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"), rs.getString("currency"),
                        rs.getObject("source_line_id", UUID.class), rs.getObject("reverses_movement_id", UUID.class),
                        rs.getObject("sale_item_id", UUID.class), rs.getObject("recipe_version_id", UUID.class),
                        rs.getObject("business_date", LocalDate.class), rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    public Page<SourceRef> sourceIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        List<SourceRef> page = jdbc.sql("""
                        SELECT source_type, source_id FROM inventory.stock_movement
                        WHERE store_id = ? AND business_date BETWEEN ? AND ?
                        GROUP BY source_type, source_id
                        ORDER BY min(posting_seq) LIMIT ? OFFSET ?""")
                .params(storeId, from, to, pageable.getPageSize(), pageable.getOffset())
                .query((rs, n) -> new SourceRef(rs.getString("source_type"), rs.getObject("source_id", UUID.class)))
                .list();
        long total = jdbc.sql("""
                        SELECT count(*) FROM (SELECT DISTINCT source_type, source_id FROM inventory.stock_movement
                                              WHERE store_id = ? AND business_date BETWEEN ? AND ?) s""")
                .params(storeId, from, to).query(Long.class).single();
        return new PageImpl<>(page, pageable, total);
    }

    /** The waste records of a wasted void, in the order they were recorded. */
    public List<com.xavier.backend.inventory.WasteFact> wasteOf(UUID voidId) {
        return jdbc.sql("""
                        SELECT void_id, original_movement_id, store_id, warehouse_id, stock_item_id, sale_item_id,
                               source_line_id, quantity, unit_cost, value, currency, business_date, occurred_at
                        FROM inventory.waste_record WHERE void_id = ? ORDER BY created_at, id""")
                .param(voidId)
                .query((rs, n) -> new com.xavier.backend.inventory.WasteFact(rs.getObject("void_id", UUID.class),
                        rs.getObject("original_movement_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("stock_item_id", UUID.class),
                        rs.getObject("sale_item_id", UUID.class), rs.getObject("source_line_id", UUID.class),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"),
                        rs.getString("currency"), rs.getObject("business_date", LocalDate.class),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    /** The voids with waste records in the store's days, by their first record. */
    public Page<UUID> wasteVoidIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        List<UUID> page = jdbc.sql("""
                        SELECT void_id FROM inventory.waste_record
                        WHERE store_id = ? AND business_date BETWEEN ? AND ?
                        GROUP BY void_id ORDER BY min(created_at), void_id LIMIT ? OFFSET ?""")
                .params(storeId, from, to, pageable.getPageSize(), pageable.getOffset()).query(UUID.class).list();
        long total = jdbc.sql("""
                        SELECT count(DISTINCT void_id) FROM inventory.waste_record
                        WHERE store_id = ? AND business_date BETWEEN ? AND ?""")
                .params(storeId, from, to).query(Long.class).single();
        return new PageImpl<>(page, pageable, total);
    }

    /** A correction's variance, or a return's (one per linked line with one); empty for any other source. */
    public List<CostVarianceFact> costVariancesOf(String sourceType, UUID sourceId) {
        String which = switch (sourceType) {
            case "COST_CORRECTION" -> "v.correction_id = ?";
            case "SUPPLIER_RETURN" -> "l.return_id = ?";
            default -> null;
        };
        if (which == null) {
            return List.of();
        }
        return jdbc.sql(VARIANCES + " WHERE " + which + " ORDER BY v.id").param(sourceId)
                .query((rs, n) -> new CostVarianceFact(rs.getObject("id", UUID.class), rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("stock_item_id", UUID.class),
                        rs.getObject("receipt_line_id", UUID.class), rs.getBigDecimal("value"),
                        rs.getString("currency"), rs.getObject("business_date", LocalDate.class)))
                .list();
    }

    /** The corrections and returns with variances in the store's days, in recording order (ids are UUIDv7). */
    public Page<SourceRef> costVarianceSources(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        String sources = VARIANCES + " WHERE v.store_id = ? AND v.business_date BETWEEN ? AND ?";
        List<SourceRef> page = jdbc.sql("SELECT source_type, source_id FROM (" + sources + ") s "
                        + "GROUP BY source_type, source_id ORDER BY min(id::text), source_id LIMIT ? OFFSET ?")
                .params(storeId, from, to, pageable.getPageSize(), pageable.getOffset())
                .query((rs, n) -> new SourceRef(rs.getString("source_type"), rs.getObject("source_id", UUID.class)))
                .list();
        long total = jdbc.sql("SELECT count(DISTINCT (source_type, source_id)) FROM (" + sources + ") s")
                .params(storeId, from, to).query(Long.class).single();
        return new PageImpl<>(page, pageable, total);
    }

    public Optional<UUID> roundOf(UUID lineId) {
        return jdbc.sql("SELECT batch_id FROM inventory.consumed_line WHERE line_id = ?").param(lineId)
                .query(UUID.class).optional();
    }

    /** A variance with the source that recorded it: its correction, or the return of its return line. */
    private static final String VARIANCES = """
            SELECT v.id, CASE WHEN v.correction_id IS NOT NULL THEN 'COST_CORRECTION' ELSE 'SUPPLIER_RETURN' END
                       AS source_type,
                   coalesce(v.correction_id, l.return_id) AS source_id, v.store_id, v.warehouse_id, v.stock_item_id,
                   coalesce(v.receipt_line_id, c.receipt_line_id) AS receipt_line_id, v.value, v.currency,
                   v.business_date
            FROM inventory.cost_variance v
            LEFT JOIN inventory.supplier_return_line l ON l.id = v.return_line_id
            LEFT JOIN inventory.receipt_cost_correction c ON c.id = v.correction_id""";
}
