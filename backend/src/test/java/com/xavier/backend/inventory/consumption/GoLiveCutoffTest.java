package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** D122: only rounds confirmed at or after the store's go-live are consumed; history is never backfilled. */
@InventoryModuleTest
class GoLiveCutoffTest extends ConsumptionTestBase {

    @Test
    void aRoundBeforeGoLiveConsumesNothingAndOneAtGoLiveIsConsumed() throws Exception {
        java.time.Instant liveFrom = clock.instant().plusSeconds(60);
        kitchen = Kitchen.openLiveFrom(mvc, fixtures, ledger, tx, jdbc, orders, catalog, liveFrom);
        UUID pizza = kitchen.catalogItem(null);
        UUID flour = kitchen.stockItem("g");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
        BatchFacts before = kitchen.round(liveFrom.minusNanos(1000), OrderMode.DINE_IN,
                line(pizza, "1", "KITCHEN"));
        BatchFacts atGoLive = kitchen.round(liveFrom, OrderMode.DINE_IN,
                line(pizza, "1", "KITCHEN"));

        assertThat(consumption.consume(before.batchId())).isEmpty();
        assertThat(consumption.consume(atGoLive.batchId())).hasSize(1);

        assertThat(kitchen.movements("ORDER_LINE", before.lines().getFirst().lineId())).isEmpty();
        assertThat(kitchen.exceptions()).isEmpty();
    }

    @Test
    void aStoreWhereInventoryIsNotLiveConsumesNothing() throws Exception {
        UUID store = fixtures.newStore();
        UUID pizza = kitchen.catalogItem(null);
        kitchen.saleRecipe(pizza, null, "1", ingredient(kitchen.stockItem("g"), "250", "g"));
        BatchFacts round = new BatchFacts(UUID.randomUUID(), UUID.randomUUID(), store, Kitchen.DAY,
                OrderMode.DINE_IN, clock.instant(), List.of(line(pizza, "1", "KITCHEN")));
        Mockito.when(orders.batchFacts(round.batchId())).thenReturn(round);

        assertThat(consumption.consume(round.batchId())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE store_id = ?").param(store)
                .query(Long.class).single()).isZero();
    }
}
