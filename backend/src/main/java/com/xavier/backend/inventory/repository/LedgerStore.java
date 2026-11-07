package com.xavier.backend.inventory.repository;

import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.dto.MovementView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ledger and the balance projection in plain SQL (D113): balance rows are created with
 * {@code ON CONFLICT DO NOTHING} and locked {@code FOR UPDATE} in {@code (warehouse_id, stock_item_id)} order;
 * movements are inserted with {@code ON CONFLICT DO NOTHING} on the source key, so a repeated post adds nothing.
 */
@Repository
public class LedgerStore {

    static final String MOVEMENT_COLUMNS = """
            id, posting_seq, warehouse_id, store_id, stock_item_id, type, quantity, unit_cost, reference_unit_cost, value,
            currency,
            source_type, source_id, source_line_id, reverses_movement_id, recipe_version_id, policy_version_id,
            sale_item_id, business_date, occurred_at, posted_at, actor_kind, actor_id, reason""";

    private final JdbcClient jdbc;

    LedgerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the missing balance rows (zero), in the order given, which callers keep to the lock order. Only keys
     * with no row are inserted: an existing row is never touched here (an insert that conflicts with a row another
     * transaction has updated would wait for it), so existing rows are reached only by {@link #lock}, in order.
     */
    public void ensureBalances(List<BalanceKey> keys, Instant now) {
        String values = keys.stream().map(key -> "(?::uuid, ?::uuid, ?::int)").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            params.add(keys.get(i).warehouseId());
            params.add(keys.get(i).stockItemId());
            params.add(i);
        }
        params.add(Timestamp.from(now));
        jdbc.sql("""
                        INSERT INTO inventory.stock_balance (warehouse_id, stock_item_id, quantity, value, average_cost,
                            last_posting_seq, updated_at)
                        SELECT k.warehouse_id, k.stock_item_id, 0, 0, 0, 0, t.now
                        FROM (VALUES %s) k (warehouse_id, stock_item_id, ord)
                        CROSS JOIN (SELECT ?::timestamptz AS now) t
                        WHERE NOT EXISTS (SELECT 1 FROM inventory.stock_balance b
                                          WHERE b.warehouse_id = k.warehouse_id AND b.stock_item_id = k.stock_item_id)
                        ORDER BY k.ord
                        ON CONFLICT DO NOTHING""".formatted(values))
                .params(params).update();
    }

    /**
     * {@code SELECT … FOR UPDATE} on the balance rows of {@code keys}, taken in {@code (warehouse_id, stock_item_id)}
     * order (ORDER BY is applied before the locking clause), held until the transaction ends.
     */
    public Map<BalanceKey, StockPosition> lock(List<BalanceKey> keys) {
        String values = keys.stream().map(key -> "(?::uuid, ?::uuid)").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        keys.forEach(key -> {
            params.add(key.warehouseId());
            params.add(key.stockItemId());
        });
        Map<BalanceKey, StockPosition> locked = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT b.warehouse_id, b.stock_item_id, b.quantity, b.value, b.average_cost
                        FROM inventory.stock_balance b
                        JOIN (VALUES %s) k (warehouse_id, stock_item_id)
                          ON b.warehouse_id = k.warehouse_id AND b.stock_item_id = k.stock_item_id
                        ORDER BY b.warehouse_id, b.stock_item_id
                        FOR UPDATE OF b""".formatted(values))
                .params(params)
                .query((rs, n) -> locked.put(new BalanceKey(rs.getObject("warehouse_id", UUID.class),
                        rs.getObject("stock_item_id", UUID.class)), position(rs))).list();
        return locked;
    }

    public void updateBalance(BalanceKey key, StockPosition position, long lastPostingSeq, Instant now) {
        jdbc.sql("""
                        UPDATE inventory.stock_balance
                        SET quantity = ?, value = ?, average_cost = ?, last_posting_seq = ?, updated_at = ?
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(position.quantity(), position.value(), position.averageCost(), lastPostingSeq,
                        Timestamp.from(now), key.warehouseId(), key.stockItemId())
                .update();
    }

    /** Inserts the movement unless its source key is taken: empty when it was, and nothing is written. */
    public Optional<MovementView> insert(NewMovement m) {
        return jdbc.sql("""
                        INSERT INTO inventory.stock_movement (id, warehouse_id, store_id, stock_item_id, type, quantity,
                            unit_cost, reference_unit_cost, value, currency, source_type, source_id, source_line_id,
                            reverses_movement_id, recipe_version_id, policy_version_id, sale_item_id, business_date,
                            occurred_at, posted_at, actor_kind, actor_id, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source_type, source_id, stock_item_id, type, warehouse_id) DO NOTHING
                        RETURNING %s""".formatted(MOVEMENT_COLUMNS))
                .params(m.id(), m.warehouseId(), m.storeId(), m.stockItemId(), m.type(), m.quantity(), m.unitCost(),
                        m.referenceUnitCost(), m.value(), m.currency(), m.sourceType(), m.sourceId(), m.sourceLineId(),
                        m.reversesMovementId(), m.recipeVersionId(), m.policyVersionId(), m.saleItemId(),
                        m.businessDate(), Timestamp.from(m.occurredAt()), Timestamp.from(m.postedAt()), m.actorKind(),
                        m.actorId(), m.reason())
                .query(LedgerStore::movement).optional();
    }

    public Optional<MovementView> findBySourceKey(String sourceType, UUID sourceId, UUID stockItemId, String type,
                                                  UUID warehouseId) {
        return jdbc.sql("""
                        SELECT %s FROM inventory.stock_movement
                        WHERE source_type = ? AND source_id = ? AND stock_item_id = ? AND type = ? AND warehouse_id = ?"""
                        .formatted(MOVEMENT_COLUMNS))
                .params(sourceType, sourceId, stockItemId, type, warehouseId)
                .query(LedgerStore::movement).optional();
    }

    static StockPosition position(ResultSet rs) throws SQLException {
        return new StockPosition(rs.getBigDecimal("quantity"), rs.getBigDecimal("value"),
                rs.getBigDecimal("average_cost"));
    }

    static MovementView movement(ResultSet rs, int row) throws SQLException {
        return new MovementView(rs.getObject("id", UUID.class), rs.getLong("posting_seq"),
                rs.getObject("warehouse_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("stock_item_id", UUID.class), rs.getString("type"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"), rs.getString("currency"),
                rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getObject("source_line_id", UUID.class), rs.getObject("reverses_movement_id", UUID.class),
                rs.getObject("recipe_version_id", UUID.class), rs.getObject("policy_version_id", UUID.class),
                rs.getObject("sale_item_id", UUID.class), rs.getObject("business_date", LocalDate.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("posted_at").toInstant(),
                rs.getString("actor_kind"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                rs.getBigDecimal("reference_unit_cost"));
    }

    /** A movement row about to be inserted, valued and attributed. */
    public record NewMovement(UUID id, UUID warehouseId, UUID storeId, UUID stockItemId, String type,
                              BigDecimal quantity, BigDecimal unitCost, BigDecimal referenceUnitCost,
                              BigDecimal value, String currency,
                              String sourceType, UUID sourceId, UUID sourceLineId, UUID reversesMovementId,
                              UUID recipeVersionId, UUID policyVersionId, UUID saleItemId, LocalDate businessDate,
                              Instant occurredAt, Instant postedAt, String actorKind, UUID actorId, String reason) {
    }
}
