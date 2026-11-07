package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D116, D133 "a redelivered round is consumed twice": the movements' source key and insert-or-ignore make a second
 * delivery post nothing new, through the service and through the persisted listener. The restart half (a second
 * application on the same database) is {@code ConsumptionRestartTest}.
 */
@InventoryModuleTest
class ConsumptionIdempotencyTest extends ConsumptionTestBase {

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void aSecondConsumptionOfTheSameRoundReplaysItsMovements() throws Exception {
        BatchFacts round = pizzaRound();

        List<MovementView> first = consumption.consume(round.batchId());
        long movements = kitchen.movementsOfStore();
        List<MovementView> second = consumption.consume(round.batchId());

        assertThat(first).hasSize(2);
        assertThat(second).isEmpty();
        assertThat(kitchen.movementsOfStore()).isEqualTo(movements);
        assertThat(kitchen.quantity(kitchen.kitchen, flourOf(round))).isEqualByComparingTo("500");
    }

    @Test
    void aRedeliveredEventPostsNothingNew() throws Exception {
        BatchFacts round = pizzaRound();
        OrderBatchConfirmed event = new OrderBatchConfirmed(round.orderId(), round.batchId(), 1, kitchen.store,
                UUID.randomUUID(), 1, "M1", UUID.randomUUID(), round.confirmedAt(), List.of());

        publish(event);
        waitUntil(() -> completed(round.batchId()) == 1);
        long movements = kitchen.movementsOfStore();
        publish(event);
        waitUntil(() -> completed(round.batchId()) == 2);

        assertThat(movements).isEqualTo(3); // the receipt, flour and salt
        assertThat(kitchen.movementsOfStore()).isEqualTo(movements);
        assertThat(kitchen.quantity(kitchen.kitchen, flourOf(round))).isEqualByComparingTo("500");
    }

    /** A redelivery never re-plans a consumed line: a route changed since does not move its stock. */
    @Test
    void aRedeliveryAfterARouteChangePostsNothingNew() throws Exception {
        BatchFacts round = pizzaRound();
        consumption.consume(round.batchId());
        UUID other = fixtures.newWarehouse(kitchen.store, "ALLOW");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(com.xavier.backend.inventory.InventoryFixtures.BO + "/consumption-routes/"
                                + kitchen.defaultRoute)
                        .with(com.xavier.backend.inventory.InventoryFixtures.account(null,
                                com.xavier.backend.access.Permission.INVENTORY_MANAGE))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"%s\", \"version\": 0}".formatted(other)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        long movements = kitchen.movementsOfStore();

        consumption.consume(round.batchId());

        assertThat(kitchen.movementsOfStore()).isEqualTo(movements);
        assertThat(kitchen.quantity(other, flour)).isEqualByComparingTo("0");
    }

    private UUID flour;

    private BatchFacts pizzaRound() throws Exception {
        UUID pizza = kitchen.catalogItem(null);
        flour = kitchen.stockItem("g");
        UUID salt = kitchen.stockItem("g");
        kitchen.receive(kitchen.kitchen, flour, "1000", "0.010000");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g") + "," + ingredient(salt, "2", "g"));
        return kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "2", "KITCHEN"));
    }

    private UUID flourOf(BatchFacts round) {
        return flour;
    }

    private void publish(Object event) {
        tx.executeWithoutResult(status -> events.publishEvent(event));
    }

    private long completed(UUID batch) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE listener_id LIKE 'com.xavier.backend.inventory.consumption.RoundConsumptionListener%'
                          AND serialized_event LIKE :batch AND completion_date IS NOT NULL""")
                .param("batch", "%" + batch + "%").query(Long.class).single();
    }

    static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now(java.time.Clock.systemUTC()).plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now(java.time.Clock.systemUTC()).isAfter(deadline)) {
                throw new AssertionError("condition not reached in 60 s");
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
    }
}
