package com.xavier.backend.inventory.consumption.repository;

import com.xavier.backend.inventory.consumption.model.ExceptionKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What consumption (D116, D117) records and reads besides the ledger: the lines it consumed, its exceptions, the
 * voids waiting for their line, and the sale movements a void reverses. Plain SQL; every insert is insert-or-ignore,
 * so a redelivered event adds nothing.
 */
@Repository
public class ConsumptionBook {

    private final JdbcClient jdbc;

    ConsumptionBook(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes, until the transaction ends, everything that consumes or reverses these order lines (a round, a
     * void, a reprocess), in id order. Taken before any stock balance lock, so the D132 order holds.
     */
    public void lockLines(Collection<UUID> lineIds) {
        lineIds.stream().distinct().sorted().forEach(id -> jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 47))").param(id.toString())
                .query((rs, n) -> 1).list());
    }

    public void recordConsumedLine(UUID lineId, UUID batchId, UUID storeId, BigDecimal quantity, Instant confirmedAt) {
        jdbc.sql("""
                        INSERT INTO inventory.consumed_line (line_id, batch_id, store_id, quantity, confirmed_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (line_id) DO NOTHING""")
                .params(lineId, batchId, storeId, quantity, java.sql.Timestamp.from(confirmedAt)).update();
    }

    public Optional<BigDecimal> consumedQuantity(UUID lineId) {
        return jdbc.sql("SELECT quantity FROM inventory.consumed_line WHERE line_id = ?").param(lineId)
                .query(BigDecimal.class).optional();
    }

    /** @return whether it was recorded now (false: the same source already has an exception of this kind) */
    public boolean recordException(UUID storeId, String sourceType, UUID sourceId, UUID itemId, ExceptionKind kind,
                                   UUID batchId, UUID lineId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO inventory.consumption_exception
                            (id, store_id, source_type, source_id, item_id, kind, batch_id, line_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source_type, source_id, kind) DO NOTHING""")
                .params(UuidVersion7Strategy.INSTANCE.generateUuid(null), storeId, sourceType, sourceId, itemId,
                        kind.name(), batchId, lineId, java.sql.Timestamp.from(now)).update() == 1;
    }

    /** Remembers a void of a round line, for good: every part of the line consumed later applies it too. */
    public void recordLineVoid(UUID voidId, UUID lineId, UUID storeId, Instant now) {
        jdbc.sql("""
                        INSERT INTO inventory.line_void (void_id, consumed_line_id, store_id, created_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (void_id) DO NOTHING""")
                .params(voidId, lineId, storeId, java.sql.Timestamp.from(now)).update();
    }

    /** Every void of a round line, the first first. */
    public List<UUID> lineVoids(UUID lineId) {
        return jdbc.sql("""
                        SELECT void_id FROM inventory.line_void WHERE consumed_line_id = ?
                        ORDER BY created_at, void_id""")
                .param(lineId).query(UUID.class).list();
    }

    /** The voids of the store whose line consumption has not settled yet (held by an exception or late). */
    public long pendingVoids(UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM inventory.line_void v
                        WHERE v.store_id = ?
                          AND NOT EXISTS (SELECT 1 FROM inventory.consumed_line c WHERE c.line_id = v.consumed_line_id)""")
                .param(storeId).query(Long.class).single();
    }

    /** The SALE_CONSUMPTION movements of an order line (its own, or its menu components'), in posting order. */
    public List<SaleMovement> saleMovementsOf(UUID lineId) {
        return jdbc.sql("""
                        SELECT id, store_id, warehouse_id, stock_item_id, -quantity AS consumed, unit_cost, currency,
                               source_type, source_id, recipe_version_id, policy_version_id, sale_item_id
                        FROM inventory.stock_movement
                        WHERE source_line_id = ? AND type = 'SALE_CONSUMPTION' ORDER BY posting_seq""")
                .param(lineId)
                .query((rs, n) -> new SaleMovement(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("stock_item_id", UUID.class),
                        rs.getBigDecimal("consumed"), rs.getBigDecimal("unit_cost"), rs.getString("currency"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class), rs.getObject("recipe_version_id", UUID.class),
                        rs.getObject("policy_version_id", UUID.class), rs.getObject("sale_item_id", UUID.class)))
                .list();
    }

    /** How much of a sale movement voids already settled (put back, or reclassified as waste); positive. */
    public BigDecimal settledQuantity(UUID movementId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT sum(quantity) FROM inventory.stock_movement
                                         WHERE reverses_movement_id = :id AND type = 'CONSUMPTION_REVERSAL'), 0)
                             + coalesce((SELECT sum(quantity) FROM inventory.waste_record
                                         WHERE original_movement_id = :id), 0)""")
                .param("id", movementId).query(BigDecimal.class).single();
    }

    /** Whether {@code voidId} already settled sale movement {@code movementId} (its reversal or its waste). */
    public boolean settledBy(UUID movementId, UUID voidId, String reversalSourceType, UUID reversalSourceId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM inventory.stock_movement
                                       WHERE reverses_movement_id = :id AND source_type = :type
                                         AND source_id = :source)
                            OR EXISTS (SELECT 1 FROM inventory.waste_record
                                       WHERE original_movement_id = :id AND void_id = :void)""")
                .param("id", movementId).param("type", reversalSourceType).param("source", reversalSourceId)
                .param("void", voidId).query(Boolean.class).single();
    }

    /** A wasted void's share of a sale movement, at its unit cost (D117 as amended): no stock moves. */
    public void recordWaste(UUID voidId, SaleMovement original, UUID lineId, BigDecimal quantity, BigDecimal value,
                            java.time.LocalDate businessDate, Instant occurredAt, String reason, Instant now) {
        jdbc.sql("""
                        INSERT INTO inventory.waste_record
                            (id, void_id, original_movement_id, store_id, warehouse_id, stock_item_id, sale_item_id,
                             source_line_id, quantity, unit_cost, value, currency, business_date, occurred_at, reason,
                             created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (void_id, original_movement_id) DO NOTHING""")
                .params(UuidVersion7Strategy.INSTANCE.generateUuid(null), voidId, original.id(), original.storeId(),
                        original.warehouseId(), original.stockItemId(), original.saleItemId(), lineId, quantity,
                        original.unitCost(), value, original.currency(), businessDate,
                        java.sql.Timestamp.from(occurredAt), reason, java.sql.Timestamp.from(now)).update();
    }

    /**
     * A sale movement as a void reverses it: {@code consumed} is its quantity, positive, at {@code unitCost}.
     */
    public record SaleMovement(UUID id, UUID storeId, UUID warehouseId, UUID stockItemId, BigDecimal consumed,
                               BigDecimal unitCost, String currency, String sourceType, UUID sourceId,
                               UUID recipeVersionId, UUID policyVersionId, UUID saleItemId) {
    }
}
