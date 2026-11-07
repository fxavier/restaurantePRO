package com.xavier.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one posting path (D113, PRD S42 {@code StockLedger.post}, MANDATORY): it values each movement against the
 * locked balance, inserts it once per source key ({@code ON CONFLICT DO NOTHING}; a replay returns the recorded
 * row, a different payload is a 409), moves the balance, and publishes {@code StockMovementsPosted} once per source
 * and transaction.
 */
@InventoryModuleTest
@RecordApplicationEvents
class StockLedgerTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-06");
    static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    UUID store;
    UUID warehouse;
    UUID item;

    @BeforeEach
    void aWarehouseAndAnItem() throws Exception {
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("kg");
    }

    @Test
    void receiptsAndASaleMoveTheBalanceAtTheMovingAverage() {
        post(receipt("10", "10.00"));
        post(receipt("10", "12.00"));

        List<MovementView> sale = post(PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION,
                new BigDecimal("-5"), "ORDER_LINE", UUID.randomUUID(), DAY, AT));

        assertThat(sale).singleElement().satisfies(movement -> {
            assertThat(movement.unitCost()).isEqualByComparingTo("11");
            assertThat(movement.value()).isEqualByComparingTo("-55");
            assertThat(movement.storeId()).isEqualTo(store);
            assertThat(movement.currency()).isEqualTo("MZN");
            assertThat(movement.actorKind()).isEqualTo("SYSTEM");
        });
        assertThat(balance()).isEqualTo("15.000000|165.0000|11.000000");
    }

    @Test
    void aReplayOfTheSameSourceReturnsTheRecordedMovementAndPostsNothing() {
        PostMovement receipt = receipt("4", "2.50");
        MovementView first = post(receipt).getFirst();
        events.clear();

        List<MovementView> again = post(receipt);

        assertThat(again).singleElement().isEqualTo(first);
        assertThat(movements()).isEqualTo(1);
        assertThat(balance()).isEqualTo("4.000000|10.0000|2.500000");
        assertThat(events.stream(StockMovementsPosted.class)).isEmpty();
    }

    @Test
    void aReplayWithAnotherQuantityIsAConflictAndPostsNothing() {
        UUID source = UUID.randomUUID();
        post(new PostMovement(warehouse, item, MovementType.RECEIPT, new BigDecimal("4"), new BigDecimal("2.5"),
                null, "GOODS_RECEIPT", source, null, null, null, null, null, DAY, AT, null, false));

        assertThatThrownBy(() -> post(new PostMovement(warehouse, item, MovementType.RECEIPT, new BigDecimal("5"),
                new BigDecimal("2.5"), null, "GOODS_RECEIPT", source, null, null, null, null, null, DAY, AT, null,
                false)))
                .isInstanceOfSatisfying(InventoryConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("MOVEMENT_SOURCE_MISMATCH"));
        assertThat(balance()).isEqualTo("4.000000|10.0000|2.500000");
    }

    @Test
    void anInBackFromNegativePostsARevaluationWithTheSameSource() {
        post(receipt("4", "10"));
        post(PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, new BigDecimal("-6"), "ORDER_LINE",
                UUID.randomUUID(), DAY, AT));

        List<MovementView> receipt = post(receipt("10", "12"));

        assertThat(receipt).extracting(MovementView::type).containsExactly("RECEIPT", "REVALUATION");
        assertThat(receipt.get(1).quantity()).isEqualByComparingTo("0");
        assertThat(receipt.get(1).value()).isEqualByComparingTo("-4");
        assertThat(receipt.get(1).sourceId()).isEqualTo(receipt.get(0).sourceId());
        assertThat(balance()).isEqualTo("8.000000|96.0000|12.000000");
    }

    @Test
    void oneEventPerSourceAndTransactionWhateverTheNumberOfMovements() {
        UUID other = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID secondItem = newItem();
        tx.executeWithoutResult(status -> {
            ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.RETURN_TO_STOCK, BigDecimal.ONE,
                            "MANUAL_MOVEMENT", source, DAY, AT),
                    PostMovement.of(warehouse, secondItem, MovementType.RETURN_TO_STOCK, BigDecimal.ONE,
                            "MANUAL_MOVEMENT", source, DAY, AT)));
            ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.WASTE, BigDecimal.ONE.negate(),
                    "MANUAL_MOVEMENT", source, DAY, AT)));
            ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.WASTE, BigDecimal.ONE.negate(),
                    "MANUAL_MOVEMENT", other, DAY, AT)));
        });

        assertThat(events.stream(StockMovementsPosted.class).filter(e -> e.storeId().equals(store)))
                .containsExactlyInAnyOrder(new StockMovementsPosted("MANUAL_MOVEMENT", source, store, DAY),
                        new StockMovementsPosted("MANUAL_MOVEMENT", other, store, DAY));
    }

    @Test
    void postingNeedsTheCallersTransaction() {
        assertThatThrownBy(() -> ledger.post(List.of(receipt("1", "1"))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aMovementWithTheWrongSignIsRefusedBeforeAnythingIsPosted() {
        assertThatThrownBy(() -> PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, BigDecimal.ONE,
                "ORDER_LINE", UUID.randomUUID(), DAY, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostMovement.of(warehouse, item, MovementType.RECEIPT, BigDecimal.ONE.negate(),
                "GOODS_RECEIPT", UUID.randomUUID(), DAY, AT)).isInstanceOf(IllegalArgumentException.class);
    }

    private PostMovement receipt(String quantity, String cost) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal(quantity), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY, AT).atCost(new BigDecimal(cost));
    }

    private List<MovementView> post(PostMovement movement) {
        return tx.execute(status -> ledger.post(List.of(movement)));
    }

    private UUID newItem() {
        try {
            return fixtures.newStockItem("un");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String balance() {
        return jdbc.sql("""
                        SELECT quantity || '|' || value || '|' || average_cost FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item).query(String.class).single();
    }

    private long movements() {
        return jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(Long.class).single();
    }
}
