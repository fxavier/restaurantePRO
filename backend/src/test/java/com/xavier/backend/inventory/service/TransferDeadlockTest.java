package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.service.Concurrency.await;
import static com.xavier.backend.inventory.service.Concurrency.backendPid;
import static com.xavier.backend.inventory.service.Concurrency.someoneBlockedBy;
import static com.xavier.backend.inventory.service.Concurrency.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.inventory.StoreTasks;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.Permission;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A same-store transfer posts its OUT and its IN in one transaction, so it locks both balance rows up front in the
 * ledger's order (D113, D132): opposite transfers between two warehouses, or a transfer against a sorted posting on
 * the same two rows, never deadlock.
 */
@InventoryModuleTest
class TransferDeadlockTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-06");
    static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

    @Autowired
    StoreScopes scopes;

    @Autowired
    MockMvc mvc;

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID low;
    UUID high;
    UUID item;

    @BeforeEach
    void twoWarehousesOfOneStoreInLockOrder() throws Exception {
        store = fixtures.newStore();
        item = fixtures.newStockItem("un");
        List<BalanceKey> keys = new ArrayList<>(List.of(new BalanceKey(fixtures.newWarehouse(store, "ALLOW"), item),
                new BalanceKey(fixtures.newWarehouse(store, "ALLOW"), item)));
        keys.sort(BalanceKey.LOCK_ORDER);
        low = keys.get(0).warehouseId();
        high = keys.get(1).warehouseId();
        tx.executeWithoutResult(status -> ledger.post(List.of(receipt(low), receipt(high))));
    }

    /**
     * Another transaction holds {@code low}. A transfer from {@code high} to {@code low} must wait for {@code low}
     * before touching {@code high}, so {@code high} stays free (a NOWAIT lock on it succeeds).
     */
    @Test
    void aTransferWaitsForTheFirstRowInLockOrderWithoutHoldingTheOther() throws Exception {
        UUID transfer = draft(high, low);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holder = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> hold = pool.submit(StoreTasks.inStore(scopes, store, () -> tx.executeWithoutResult(status -> {
                ledger.post(List.of(sale(low)));
                holder.set(backendPid(jdbc));
                holding.countDown();
                await(release);
            })));
            await(holding);
            Future<Integer> ship = pool.submit(() -> ship(transfer));
            try {
                waitUntil(() -> ship.isDone() || someoneBlockedBy(jdbc, holder.get()));
                assertThat(ship.isDone()).as("the transfer waits for the held row").isFalse();
                assertThat(isFree(high)).as("the transfer holds no lock on the row that sorts later").isTrue();
            } finally {
                release.countDown();
            }
            hold.get(60, TimeUnit.SECONDS);
            assertThat(ship.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
    }

    @Test
    void oppositeTransfersStartedTogetherAllSucceed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 10; i++) {
                UUID forward = draft(low, high);
                UUID backward = draft(high, low);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> a = pool.submit(() -> {
                    await(start);
                    return ship(forward);
                });
                Future<Integer> b = pool.submit(() -> {
                    await(start);
                    return ship(backward);
                });
                start.countDown();
                assertThat(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS))).containsOnly(200);
            }
        }
        assertThat(quantity(low)).isEqualByComparingTo("100");
        assertThat(quantity(high)).isEqualByComparingTo("100");
    }

    @Test
    void aTransferAndASortedPostingOnTheSameRowsAllSucceed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 10; i++) {
                UUID transfer = draft(high, low);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> a = pool.submit(() -> {
                    await(start);
                    return ship(transfer);
                });
                Future<?> b = pool.submit(StoreTasks.inStore(scopes, store, () -> {
                    await(start);
                    tx.executeWithoutResult(status -> ledger.post(List.of(sale(low), sale(high))));
                }));
                start.countDown();
                assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(200);
                b.get(60, TimeUnit.SECONDS);
            }
        }
        assertThat(quantity(low)).isEqualByComparingTo("100");
        assertThat(quantity(high)).isEqualByComparingTo("80");
    }

    private UUID draft(UUID from, UUID to) throws Exception {
        return id(mvc.perform(idem(post(BO + "/transfers")).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(from, to, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private int ship(UUID transfer) throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/ship")).with(manager()))
                .andReturn().getResponse().getStatus();
    }

    private boolean isFree(UUID warehouse) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            status.setRollbackOnly();
            try {
                jdbc.sql("""
                                SELECT 1 FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?
                                FOR UPDATE NOWAIT""").params(warehouse, item).query(Integer.class).single();
                return true;
            } catch (org.springframework.dao.DataAccessException lockNotAvailable) {
                return false;
            }
        }));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return account(store, Permission.INVENTORY_TRANSFER);
    }

    private PostMovement receipt(UUID warehouse) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal("100"), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY, AT).atCost(BigDecimal.ONE);
    }

    private PostMovement sale(UUID warehouse) {
        return PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, BigDecimal.ONE.negate(), "ORDER_LINE",
                UUID.randomUUID(), DAY, AT);
    }

    private BigDecimal quantity(UUID warehouse) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).single();
    }
}
