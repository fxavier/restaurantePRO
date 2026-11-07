package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.service.Concurrency.await;
import static com.xavier.backend.inventory.service.Concurrency.backendPid;
import static com.xavier.backend.inventory.service.Concurrency.someoneBlockedBy;
import static com.xavier.backend.inventory.service.Concurrency.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * Two multi-item postings never deadlock (D113, D132, D133 {@code StockPostingDeadlockTest}): every posting locks its
 * balance rows in {@code (warehouse_id, stock_item_id)} order, whatever the order of its movements. So a posting
 * that needs a row another transaction holds waits for it while holding none of the rows that sort after it.
 */
@InventoryModuleTest
class StockPostingDeadlockTest {

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
    UUID first;
    UUID second;

    @BeforeEach
    void twoItemsInLockOrder() throws Exception {
        warehouse = fixtures.newWarehouse(fixtures.newStore(), "ALLOW");
        List<BalanceKey> keys = new ArrayList<>(List.of(new BalanceKey(warehouse, fixtures.newStockItem("un")),
                new BalanceKey(warehouse, fixtures.newStockItem("un"))));
        keys.sort(BalanceKey.LOCK_ORDER);
        first = keys.get(0).stockItemId();
        second = keys.get(1).stockItemId();
        tx.executeWithoutResult(status -> ledger.post(List.of(receipt(first), receipt(second))));
    }

    /**
     * A holds {@code first} and will take {@code second} later. B posts {@code second} then {@code first}: it must
     * wait for {@code first} before touching {@code second}, so {@code second} stays free (a NOWAIT lock on it
     * succeeds) and A's later lock on it does not deadlock.
     */
    @Test
    void aPostingInReverseOrderWaitsWithoutHoldingTheRowThatSortsLater() throws Exception {
        CountDownLatch aHoldsFirst = new CountDownLatch(1);
        CountDownLatch aMayContinue = new CountDownLatch(1);
        AtomicInteger aPid = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
                ledger.post(List.of(sale(first)));
                aPid.set(backendPid(jdbc));
                aHoldsFirst.countDown();
                await(aMayContinue);
                ledger.post(List.of(sale(second)));
            }));
            await(aHoldsFirst);
            Future<?> b = pool.submit(() -> tx.executeWithoutResult(status ->
                    ledger.post(List.of(sale(second), sale(first)))));
            try {
                waitUntil(() -> b.isDone() || someoneBlockedBy(jdbc, aPid.get()));
                assertThat(b.isDone()).as("B waits for A's lock on the first row").isFalse();

                assertThat(secondIsFree()).as("B holds no lock on the row that sorts after the one it waits for")
                        .isTrue();
            } finally {
                aMayContinue.countDown();
            }
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        }
        assertThat(quantity(first)).isEqualByComparingTo("98");
        assertThat(quantity(second)).isEqualByComparingTo("98");
    }

    /** Opposite orders started together, many times: every posting commits, none is a deadlock victim. */
    @Test
    void oppositeOrdersStartedTogetherAllCommit() throws Exception {
        int rounds = 20;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < rounds; i++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<?> forward = pool.submit(() -> {
                    await(start);
                    tx.executeWithoutResult(status -> ledger.post(List.of(sale(first), sale(second))));
                });
                Future<?> backward = pool.submit(() -> {
                    await(start);
                    tx.executeWithoutResult(status -> ledger.post(List.of(sale(second), sale(first))));
                });
                start.countDown();
                forward.get(60, TimeUnit.SECONDS);
                backward.get(60, TimeUnit.SECONDS);
            }
        }
        assertThat(quantity(first)).isEqualByComparingTo(String.valueOf(100 - 2 * rounds));
        assertThat(quantity(second)).isEqualByComparingTo(String.valueOf(100 - 2 * rounds));
    }

    /** A NOWAIT lock on the second row, in its own transaction, rolled back at once. */
    private boolean secondIsFree() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            status.setRollbackOnly();
            try {
                jdbc.sql("""
                                SELECT 1 FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?
                                FOR UPDATE NOWAIT""")
                        .params(warehouse, second).query(Integer.class).single();
                return true;
            } catch (org.springframework.dao.DataAccessException lockNotAvailable) {
                return false;
            }
        }));
    }

    private PostMovement receipt(UUID item) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal("100"), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY, AT).atCost(BigDecimal.ONE);
    }

    private PostMovement sale(UUID item) {
        return PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, BigDecimal.ONE.negate(), "ORDER_LINE",
                UUID.randomUUID(), DAY, AT);
    }

    private BigDecimal quantity(UUID item) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).single();
    }
}
