package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.MovementFact;
import com.xavier.backend.inventory.SourceRef;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The reads behind {@code InventoryFacts}: movements by source (the source key's index) and sources by day. */
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
}
