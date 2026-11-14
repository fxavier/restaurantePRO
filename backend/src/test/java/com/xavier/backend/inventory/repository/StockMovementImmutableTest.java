package com.xavier.backend.inventory.repository;

import com.xavier.backend.OwnerDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A stock movement is a fact (§8, D113): the database refuses UPDATE, DELETE and TRUNCATE, a quantity with the wrong
 * sign for its type, a negative unit cost and a second movement with the same source key. Corrections are new
 * movements.
 */
@InventoryModuleTest
class StockMovementImmutableTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-06");

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID warehouse;
    UUID item;
    MovementView posted;

    @BeforeEach
    void aPostedReceipt() throws Exception {
        warehouse = fixtures.newWarehouse(fixtures.newStore(), "ALLOW");
        item = fixtures.newStockItem("un");
        posted = tx.execute(status -> ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.RECEIPT,
                        new BigDecimal("3"), "GOODS_RECEIPT", UUID.randomUUID(), DAY, Instant.parse("2026-11-06T10:00:00Z"))
                .atCost(BigDecimal.ONE)))).getFirst();
    }

    @Test
    void aMovementCannotBeUpdatedDeletedOrTruncated() {
        assertRefused("UPDATE inventory.stock_movement SET quantity = 30 WHERE id = '" + posted.id() + "'");
        assertRefused("DELETE FROM inventory.stock_movement WHERE id = '" + posted.id() + "'");
        assertRefused("TRUNCATE inventory.stock_movement");
        assertThat(jdbc.sql("SELECT quantity FROM inventory.stock_movement WHERE id = ?").param(posted.id())
                .query(BigDecimal.class).single()).isEqualByComparingTo("3");
    }

    @Test
    void theSignOfTheQuantityIsFixedByTheType() {
        assertThatThrownBy(() -> insert("SALE_CONSUMPTION", "1", "1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("RECEIPT", "-1", "1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("REVALUATION", "1", "1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("MANUAL_ADJUSTMENT", "0", "1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("RECEIPT", "1", "-0.000001"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert("MANUAL_ADJUSTMENT", "-1", "1");
        insert("REVALUATION", "0", "1");
    }

    @Test
    void aSourceKeyHasOneMovement() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO inventory.stock_movement (id, warehouse_id, store_id, stock_item_id, type, quantity,
                            unit_cost, value, currency, source_type, source_id, business_date, occurred_at, posted_at,
                            actor_kind)
                        SELECT gen_random_uuid(), warehouse_id, store_id, stock_item_id, type, 1, 1, 1, currency,
                            source_type, source_id, business_date, occurred_at, posted_at, 'SYSTEM'
                        FROM inventory.stock_movement WHERE id = ?""").param(posted.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String type, String quantity, String unitCost) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (id, warehouse_id, store_id, stock_item_id, type, quantity,
                            unit_cost, value, currency, source_type, source_id, business_date, occurred_at, posted_at,
                            actor_kind)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 0, 'MZN', 'TEST', gen_random_uuid(), ?, now(),
                            now(), 'SYSTEM')""")
                .params(warehouse, posted.storeId(), item, type, new BigDecimal(quantity), new BigDecimal(unitCost),
                        DAY).update();
    }

    /** {@code TRUNCATE} as the owner: the pool has no TRUNCATE privilege, so only the trigger can stop the owner. */
    private void assertRefused(String sql) {
        Runnable statement = sql.startsWith("TRUNCATE")
                ? () -> OwnerDatabase.shared().rolledBack(owner -> owner.sql(sql).update())
                : () -> jdbc.sql(sql).update();
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
