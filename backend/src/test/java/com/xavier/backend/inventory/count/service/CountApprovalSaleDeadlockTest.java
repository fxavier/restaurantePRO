package com.xavier.backend.inventory.count.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.StoreTasks;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.organization.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D113/D132: a count approval and a sale on the same items never deadlock. Approval locks every balance row of the
 * count up front, in the ledger's order, before it posts; a sale (the round listener's posting) locks in the same
 * order. So whichever waits holds none of the rows that sort after the one it waits for. Latches and
 * {@code pg_blocking_pids}, no sleeps as guards.
 */
@InventoryModuleTest
class CountApprovalSaleDeadlockTest {

    @Autowired
    StoreScopes scopes;

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    StockLedger ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID first;
    UUID second;
    Instant now;

    @BeforeEach
    void tenOfEachInLockOrder() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc, clock);
        store = fixtures.newActiveStore();
        clock.advance(Duration.ofHours(4)); // history below happens after go-live (now)
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        List<BalanceKey> keys = new ArrayList<>(List.of(new BalanceKey(warehouse, fixtures.newStockItem("un")),
                new BalanceKey(warehouse, fixtures.newStockItem("un"))));
        keys.sort(BalanceKey.LOCK_ORDER);
        first = keys.get(0).stockItemId();
        second = keys.get(1).stockItemId();
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        counts.receipt(warehouse, first, "10", "1", now.minus(Duration.ofHours(2)));
        counts.receipt(warehouse, second, "10", "1", now.minus(Duration.ofHours(2)));
    }

    /**
     * The sale holds {@code first} and will post {@code second} next. The approval waits for {@code first} without
     * holding {@code second} (a NOWAIT lock on it succeeds), so the sale's second posting goes through and both
     * commit. The sale is after the cut-off, so it is not counted against the lines.
     */
    @Test
    void anApprovalWaitingForASaleHoldsNothingTheSaleNeedsNext() throws Exception {
        UUID count = submittedCount();
        CountDownLatch saleHoldsFirst = new CountDownLatch(1);
        CountDownLatch saleMayContinue = new CountDownLatch(1);
        AtomicInteger salePid = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> sale = pool.submit(StoreTasks.inStore(scopes, store, () -> tx.executeWithoutResult(status -> {
                ledger.post(List.of(sale(first)));
                salePid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                saleHoldsFirst.countDown();
                await(saleMayContinue);
                ledger.post(List.of(sale(second)));
            })));
            await(saleHoldsFirst);
            Future<Integer> approval = pool.submit(() -> counts.approve(store, count).andReturn().getResponse()
                    .getStatus());
            try {
                waitUntil(() -> approval.isDone() || someoneBlockedBy(salePid.get()));
                assertThat(approval.isDone()).as("the approval waits for the sale's lock on the first row").isFalse();
                assertThat(secondIsFree()).as("the waiting approval holds no lock on the row that sorts later")
                        .isTrue();
            } finally {
                saleMayContinue.countDown();
            }
            sale.get(60, TimeUnit.SECONDS);
            assertThat(approval.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(counts.balance(warehouse, first)).isEqualTo("7.000000|7.0000");
        assertThat(counts.balance(warehouse, second)).isEqualTo("7.000000|7.0000");
    }

    /** Approvals and sales in the reverse item order, started together: every one commits, none is a victim. */
    @Test
    void approvalsAndReverseOrderSalesStartedTogetherAllCommit() throws Exception {
        int rounds = 5;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < rounds; i++) {
                UUID count = submittedCount();
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> approval = pool.submit(() -> {
                    await(start);
                    return counts.approve(store, count).andReturn().getResponse().getStatus();
                });
                Future<?> sale = pool.submit(StoreTasks.inStore(scopes, store, () -> {
                    await(start);
                    tx.executeWithoutResult(status -> ledger.post(List.of(sale(second), sale(first))));
                }));
                start.countDown();
                assertThat(approval.get(60, TimeUnit.SECONDS)).isEqualTo(200);
                sale.get(60, TimeUnit.SECONDS);
            }
        }
        // every round sets the stock at its cut-off to the 8 counted (the earlier rounds' sales are before it);
        // the last round's sale is after it
        assertThat(counts.balance(warehouse, first)).isEqualTo("7.000000|7.0000");
        assertThat(counts.balance(warehouse, second)).isEqualTo("7.000000|7.0000");
    }

    /** Counted 10 minutes after its start; every sale below is at the clock, hours later. */
    private UUID submittedCount() throws Exception {
        UUID count = counts.startedCount(store, warehouse, "REGULAR");
        Instant countedAt = counts.at(10);
        counts.line(store, count, first, "8", countedAt, null);
        counts.line(store, count, second, "8", countedAt, null);
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT);
        return count;
    }

    private PostMovement sale(UUID item) {
        Instant at = clock.instant();
        return PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, BigDecimal.ONE.negate(), "ORDER_LINE",
                UUID.randomUUID(), LocalDate.ofInstant(at, ZoneOffset.UTC), at);
    }

    private boolean someoneBlockedBy(int pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))").param(pid)
                .query(Long.class).single() > 0;
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
            } catch (DataAccessException lockNotAvailable) {
                return false;
            }
        }));
    }

    /** Polls a condition (not a timing guard): fails the test if it never holds. */
    private static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached in 60 s");
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
