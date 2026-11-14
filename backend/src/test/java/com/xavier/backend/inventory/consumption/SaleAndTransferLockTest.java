package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.consumption.ConsumptionIdempotencyTest.waitUntil;
import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.inventory.ScopedPool;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * D132, D133: a round's consumption locks its balance rows in the ledger's order, and so does a same-store transfer
 * (which locks both of its rows first), so a sale and a transfer of the same items in opposite item order never
 * deadlock: one waits for the other, then both commit.
 */
@InventoryModuleTest
class SaleAndTransferLockTest extends ConsumptionTestBase {

    UUID first;
    UUID second;
    UUID pizza;

    @BeforeEach
    void twoItemsOnePizza() throws Exception {
        List<UUID> items = new ArrayList<>(List.of(kitchen.stockItem("un"), kitchen.stockItem("un")));
        items.sort(java.util.Comparator.comparing(item -> new BalanceKey(kitchen.kitchen, item),
                BalanceKey.LOCK_ORDER));
        first = items.get(0);
        second = items.get(1);
        kitchen.receive(kitchen.kitchen, first, "1000", "1.000000");
        kitchen.receive(kitchen.kitchen, second, "1000", "1.000000");
        pizza = kitchen.catalogItem(null);
        kitchen.saleRecipe(pizza, null, "1", ingredient(second, "1", "un") + "," + ingredient(first, "1", "un"));
    }

    @Test
    void aTransferWaitsForASaleHoldingTheSameRowsAndThenBothCommit() throws Exception {
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "1", "KITCHEN"));
        UUID transfer = draft();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holder = new AtomicInteger();
        try (ExecutorService pool = ScopedPool.of(scopes, 2)) {
            Future<?> sale = pool.submit(() -> tx.executeWithoutResult(status -> {
                consumption.consume(round.batchId());
                holder.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                holding.countDown();
                await(release);
            }));
            await(holding);
            Future<Integer> ship = pool.submit(() -> ship(transfer));
            try {
                waitUntil(() -> ship.isDone() || blockedBy(holder.get()));
                assertThat(ship.isDone()).as("the transfer waits for the sale's rows").isFalse();
            } finally {
                release.countDown();
            }
            sale.get(60, TimeUnit.SECONDS);
            assertThat(ship.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(kitchen.quantity(kitchen.kitchen, first)).isEqualByComparingTo("998");
        assertThat(kitchen.quantity(kitchen.bar, second)).isEqualByComparingTo("1");
    }

    @Test
    void salesAndTransfersStartedTogetherAllCommit() throws Exception {
        try (ExecutorService pool = ScopedPool.of(scopes, 2)) {
            for (int i = 0; i < 10; i++) {
                BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "1", "KITCHEN"));
                UUID transfer = draft();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> sale = pool.submit(() -> {
                    await(start);
                    consumption.consume(round.batchId());
                });
                Future<Integer> ship = pool.submit(() -> {
                    await(start);
                    return ship(transfer);
                });
                start.countDown();
                sale.get(60, TimeUnit.SECONDS);
                assertThat(ship.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        assertThat(kitchen.quantity(kitchen.kitchen, first)).isEqualByComparingTo("980");
        assertThat(kitchen.quantity(kitchen.kitchen, second)).isEqualByComparingTo("980");
        assertThat(kitchen.quantity(kitchen.bar, first)).isEqualByComparingTo("10");
    }

    /** A same-store transfer of both items from the kitchen to the bar, the later-sorting item first. */
    private UUID draft() throws Exception {
        return id(mvc.perform(idem(post(BO + "/transfers")).with(account(kitchen.store, Permission.INVENTORY_TRANSFER))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 1}, {"stockItemId": "%s", "quantity": 1}]}"""
                                .formatted(kitchen.kitchen, kitchen.bar, second, first)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private int ship(UUID transfer) throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/ship"))
                .with(account(kitchen.store, Permission.INVENTORY_TRANSFER))).andReturn().getResponse().getStatus();
    }

    private boolean blockedBy(int pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))").param(pid)
                .query(Long.class).single() > 0;
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
