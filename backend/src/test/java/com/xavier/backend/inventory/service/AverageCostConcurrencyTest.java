package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.service.Concurrency.await;
import static com.xavier.backend.inventory.service.Concurrency.backendPid;
import static com.xavier.backend.inventory.service.Concurrency.someoneBlockedBy;
import static com.xavier.backend.inventory.service.Concurrency.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A receipt and a consumption of the same item at once (D113, D133 {@code AverageCostConcurrencyTest}): the balance
 * row lock serializes them, so each movement is valued at the average left by the one before it in posting order.
 * Σ movements = balance, and a serial replay of the ledger gives the same balance and average.
 */
@InventoryModuleTest
class AverageCostConcurrencyTest {

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

    UUID warehouse;
    UUID item;

    @BeforeEach
    void anItemWith10At10() throws Exception {
        warehouse = fixtures.newWarehouse(fixtures.newStore(), "ALLOW");
        item = fixtures.newStockItem("kg");
        tx.executeWithoutResult(status -> ledger.post(List.of(receipt("10", "10"))));
    }

    /** A sale started while a receipt at 12 is uncommitted waits for it, then is valued at the new average 11. */
    @Test
    void aSaleWaitsForTheReceiptInFlightAndIsValuedAtTheAverageItLeaves() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        AtomicInteger receiver = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> receipt = pool.submit(() -> tx.executeWithoutResult(status -> {
                ledger.post(List.of(receipt("10", "12")));
                receiver.set(backendPid(jdbc));
                received.countDown();
                await(mayCommit);
            }));
            await(received);
            Future<List<MovementView>> sale = pool.submit(() -> tx.execute(status -> ledger.post(List.of(
                    PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, new BigDecimal("-4"),
                            "ORDER_LINE", UUID.randomUUID(), DAY, AT)))));
            try {
                waitUntil(() -> sale.isDone() || someoneBlockedBy(jdbc, receiver.get()));
                assertThat(sale.isDone()).as("the sale waits for the receipt's balance lock").isFalse();
            } finally {
                mayCommit.countDown();
            }
            receipt.get(60, TimeUnit.SECONDS);
            assertThat(sale.get(60, TimeUnit.SECONDS)).singleElement().satisfies(movement -> {
                assertThat(movement.unitCost()).isEqualByComparingTo("11");
                assertThat(movement.value()).isEqualByComparingTo("-44");
            });
        }
        assertThat(balance()).isEqualTo(new StockPosition(new BigDecimal("16"), new BigDecimal("176"),
                new BigDecimal("11")));
    }

    /**
     * Four threads post receipts at varied costs and consumptions (some below zero) on the same item, from a fixed
     * seed. Whatever the interleaving: Σ movements = balance, every movement was valued at the position the
     * previous one in posting order left, and replaying the ledger gives the stored balance and average.
     */
    @Test
    void concurrentReceiptsAndSalesKeepTheLedgerAndTheBalanceInStep() throws Exception {
        int threads = 4;
        int perThread = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                Random random = new Random(42L + t);
                done.add(pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < perThread; i++) {
                        PostMovement movement = random.nextInt(3) == 0
                                ? receipt(String.valueOf(1 + random.nextInt(9)),
                                new BigDecimal(5 + random.nextInt(1000)).movePointLeft(2).toPlainString())
                                : PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION,
                                new BigDecimal(1 + random.nextInt(3000)).movePointLeft(3).negate(), "ORDER_LINE",
                                UUID.randomUUID(), DAY, AT);
                        tx.executeWithoutResult(status -> ledger.post(List.of(movement)));
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(120, TimeUnit.SECONDS);
            }
        }

        List<Recorded> ledgerRows = jdbc.sql("""
                        SELECT type, quantity, unit_cost, value FROM inventory.stock_movement
                        WHERE warehouse_id = ? AND stock_item_id = ? ORDER BY posting_seq""")
                .params(warehouse, item)
                .query((rs, n) -> new Recorded(rs.getString("type"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"))).list();
        assertThat(ledgerRows).filteredOn(row -> !row.type().equals("REVALUATION"))
                .hasSize(1 + threads * perThread);

        StockPosition replayed = StockPosition.empty();
        for (Recorded row : ledgerRows) {
            if (row.type().equals("SALE_CONSUMPTION")) {
                assertThat(row.unitCost()).as("a sale is valued at the average the previous movement left")
                        .isEqualByComparingTo(replayed.averageCost());
            }
            replayed = replayed.after(row.quantity(), row.value(), row.unitCost());
        }
        assertThat(balance()).isEqualTo(replayed);
        assertThat(balance().quantity()).isEqualByComparingTo(sum("quantity"));
        assertThat(balance().value()).isEqualByComparingTo(sum("value"));
    }

    private PostMovement receipt(String quantity, String cost) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal(quantity), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY, AT).atCost(new BigDecimal(cost));
    }

    private StockPosition balance() {
        return jdbc.sql("""
                        SELECT quantity, value, average_cost FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item)
                .query((rs, n) -> new StockPosition(rs.getBigDecimal("quantity"), rs.getBigDecimal("value"),
                        rs.getBigDecimal("average_cost"))).single();
    }

    private BigDecimal sum(String column) {
        return jdbc.sql("SELECT sum(" + column + ") FROM inventory.stock_movement WHERE warehouse_id = ? "
                + "AND stock_item_id = ?").params(warehouse, item).query(BigDecimal.class).single();
    }

    private record Recorded(String type, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }
}
