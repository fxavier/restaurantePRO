package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.consumption.Kitchen.component;
import static com.xavier.backend.inventory.consumption.Kitchen.ingredient;
import static com.xavier.backend.inventory.consumption.Kitchen.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.VoidFacts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * D117: a void reverses the consumption of the round line it descends from, in proportion and capped, at the
 * original movement's unit cost; a wasted void also records the waste, so the stock does not change.
 *
 * <p>Valuation: the CONSUMPTION_REVERSAL IN carries the original movement's unit cost (D117 "ao custo do movimento
 * original"); the WASTE OUT is valued, like every OUT, at the average after that reversal (D113), with the original
 * cost kept as its reference cost. When the average has not moved since the sale, both are the same value.
 */
@InventoryModuleTest
class VoidReversalTest extends ConsumptionTestBase {

    UUID pizza;
    UUID flour;

    @BeforeEach
    void flourAtTwoPerKilo() throws Exception {
        pizza = kitchen.catalogItem(null);
        flour = kitchen.stockItem("kg");
        kitchen.receive(kitchen.kitchen, flour, "10", "2.000000");
        kitchen.saleRecipe(pizza, null, "1", ingredient(flour, "250", "g"));
    }

    @Test
    void aVoidNotWastedPutsTheStockBackAtTheOriginalCost() throws Exception {
        BatchFacts round = sold("4");
        kitchen.receive(kitchen.kitchen, flour, "9", "4.000000"); // the average moves to 3.00 after the sale
        VoidFacts error = kitchen.voided(lineOf(round), "1", false, clock.instant());

        voids.reverse(error.voidId());

        Map<String, Object> reversal = kitchen.movements("VOID", error.voidId()).getFirst();
        assertThat(reversal.get("type")).isEqualTo("CONSUMPTION_REVERSAL");
        assertThat((BigDecimal) reversal.get("quantity")).isEqualByComparingTo("0.25");
        assertThat((BigDecimal) reversal.get("unit_cost")).isEqualByComparingTo("2");
        assertThat((BigDecimal) reversal.get("value")).isEqualByComparingTo("0.5");
        assertThat(reversal.get("reverses_movement_id")).isNotNull();
        assertThat(kitchen.movements("VOID", error.voidId())).hasSize(1);
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("18.25");
    }

    /**
     * D117 as amended: the ingredients of a wasted void really were used, so no stock movement is posted; the cost is
     * reclassified from sale to waste at the original cost, even when the average moved since the sale.
     */
    @Test
    void aWastedVoidMovesNoStockAndRecordsTheWasteAtTheOriginalCost() throws Exception {
        BatchFacts round = sold("2");
        kitchen.receive(kitchen.kitchen, flour, "9.5", "4.000000"); // the average moves from 2.00 to 3.00
        BigDecimal quantity = kitchen.quantity(kitchen.kitchen, flour);
        BigDecimal value = kitchen.value(kitchen.kitchen, flour);
        VoidFacts dropped = kitchen.voided(lineOf(round), "2", true, clock.instant());

        voids.reverse(dropped.voidId());

        assertThat(kitchen.movements("VOID", dropped.voidId())).isEmpty();
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo(quantity);
        assertThat(kitchen.value(kitchen.kitchen, flour)).isEqualByComparingTo(value);
        assertThat(kitchen.wasteRecords(dropped.voidId())).singleElement().satisfies(w -> {
            assertThat((BigDecimal) w.get("quantity")).isEqualByComparingTo("0.5");
            assertThat((BigDecimal) w.get("unit_cost")).isEqualByComparingTo("2");
            assertThat((BigDecimal) w.get("value")).isEqualByComparingTo("1.0000");
        });
        com.xavier.backend.inventory.InventoryFixtures.json(mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get(com.xavier.backend.inventory.InventoryFixtures.BO + "/reports/waste")
                                .param("storeId", kitchen.store.toString()).param("from", Kitchen.DAY.toString())
                                .param("to", Kitchen.DAY.toString())
                                .with(com.xavier.backend.inventory.InventoryFixtures.account(kitchen.store,
                                        com.xavier.backend.access.Permission.INVENTORY_VIEW)))
                .andReturn().getResponse().getContentAsString()).get("rows").forEach(row -> {
            if (row.get("stockItemId").asString().equals(flour.toString())) {
                assertThat(row.get("quantity").decimalValue()).isEqualByComparingTo("0.5");
                assertThat(row.get("value").decimalValue()).isEqualByComparingTo("1.0000");
            }
        });
        voids.reverse(dropped.voidId());
        assertThat(kitchen.wasteRecords(dropped.voidId())).hasSize(1);
    }

    /** Three voids of a third each: the last one reverses whatever rounding left, so the line nets to zero. */
    @Test
    void theVoidThatCompletesTheLineReversesTheWholeRemainder() throws Exception {
        UUID salt = kitchen.stockItem("kg");
        UUID pinch = kitchen.catalogItem(null);
        kitchen.saleRecipe(pinch, null, "3", ingredient(salt, "0.01", "g")); // 3 sold → 0.000010 kg
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pinch, "3", "KITCHEN"));
        consumption.consume(round.batchId());

        for (int i = 0; i < 3; i++) {
            voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        }

        assertThat(kitchen.quantity(kitchen.kitchen, salt)).isEqualByComparingTo("0");
    }

    /** A void applied to one component before the other's exception is reprocessed applies to that one too. */
    @Test
    void aReprocessedMenuComponentKeepsTheVoidsAlreadyApplied() throws Exception {
        UUID menu = kitchen.catalogItem(null);
        UUID drink = kitchen.catalogItem(null);
        UUID juice = kitchen.stockItem("ml");
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN,
                Kitchen.menu(menu, "2", component(pizza, "1", "KITCHEN"), component(drink, "1", "BAR")));
        consumption.consume(round.batchId());
        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("9.75");
        clock.advance(java.time.Duration.ofMinutes(1));
        kitchen.saleRecipe(drink, null, "1", ingredient(juice, "200", "ml"));

        reprocessAll();

        assertThat(kitchen.quantity(kitchen.bar, juice)).isEqualByComparingTo("-200");
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("9.75");
    }

    /** Every component failed: the void waits, then each reprocess applies it to its own component, once. */
    @Test
    void aVoidOfAMenuWhoseComponentsAllFailedAppliesToEachReprocessedComponent() throws Exception {
        UUID menu = kitchen.catalogItem(null);
        UUID starter = kitchen.catalogItem(null);
        UUID drink = kitchen.catalogItem(null);
        UUID bread = kitchen.stockItem("g");
        UUID juice = kitchen.stockItem("ml");
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN,
                Kitchen.menu(menu, "2", component(starter, "1", "KITCHEN"), component(drink, "1", "BAR")));
        consumption.consume(round.batchId());
        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        clock.advance(java.time.Duration.ofMinutes(1));
        kitchen.saleRecipe(starter, null, "1", ingredient(bread, "80", "g"));
        kitchen.saleRecipe(drink, null, "1", ingredient(juice, "200", "ml"));

        reprocessAll();

        assertThat(kitchen.quantity(kitchen.kitchen, bread)).isEqualByComparingTo("-80");
        assertThat(kitchen.quantity(kitchen.bar, juice)).isEqualByComparingTo("-200");
    }

    private void reprocessAll() throws Exception {
        for (Map<String, Object> exception : kitchen.exceptions()) {
            mvc.perform(com.xavier.backend.inventory.InventoryFixtures.idem(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    com.xavier.backend.inventory.InventoryFixtures.BO + "/consumption-exceptions/"
                                            + exception.get("id") + "/reprocess"))
                            .with(com.xavier.backend.inventory.InventoryFixtures.account(kitchen.store,
                                    com.xavier.backend.access.Permission.INVENTORY_REBUILD)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
    }

    @Test
    void partialVoidsAreCappedAtWhatWasConsumed() throws Exception {
        BatchFacts round = sold("3");
        VoidFacts first = kitchen.voided(lineOf(round), "2", false, clock.instant());
        VoidFacts second = kitchen.voided(lineOf(round), "2", false, clock.instant()); // more than is left

        voids.reverse(first.voidId());
        voids.reverse(second.voidId());

        assertThat((BigDecimal) kitchen.movements("VOID", first.voidId()).getFirst().get("quantity"))
                .isEqualByComparingTo("0.5");
        assertThat((BigDecimal) kitchen.movements("VOID", second.voidId()).getFirst().get("quantity"))
                .isEqualByComparingTo("0.25");
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("10");
    }

    /** A later void left less to reverse than the first one took: its redelivery still posts nothing. */
    @Test
    void aRedeliveredVoidPostsNothingNew() throws Exception {
        BatchFacts round = sold("3");
        VoidFacts first = kitchen.voided(lineOf(round), "1", true, clock.instant());
        voids.reverse(first.voidId());
        voids.reverse(kitchen.voided(lineOf(round), "1.5", false, clock.instant()).voidId());
        long movements = kitchen.movementsOfStore();

        assertThat(voids.reverse(first.voidId())).isEmpty();

        assertThat(kitchen.movementsOfStore()).isEqualTo(movements);
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("9.625");
    }

    @Test
    void aVoidOfALineNotConsumedYetIsReversedWhenTheLineIs() throws Exception {
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, "2", "KITCHEN"));
        VoidFacts early = kitchen.voided(lineOf(round), "1", false, clock.instant());

        assertThat(voids.reverse(early.voidId())).isEmpty();
        consumption.consume(round.batchId());

        assertThat((BigDecimal) kitchen.movements("VOID", early.voidId()).getFirst().get("quantity"))
                .isEqualByComparingTo("0.25");
        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("9.75");
        consumption.consume(round.batchId());
        assertThat(kitchen.movements("VOID", early.voidId())).hasSize(1);
    }

    @Test
    void aVoidOfALineNeverConsumedOrWithoutRoundLineReversesNothing() throws Exception {
        VoidFacts r3 = kitchen.voided(null, "1", false, clock.instant());
        VoidFacts untracked = kitchen.voided(UUID.randomUUID(), "1", true, clock.instant());

        assertThat(voids.reverse(r3.voidId())).isEmpty();
        assertThat(voids.reverse(untracked.voidId())).isEmpty();
        assertThat(kitchen.movementsOfStore()).isEqualTo(1); // the receipt
    }

    /** Its line was confirmed before the void, so before go-live too: it was never consumed and never will be. */
    @Test
    void aVoidBeforeGoLiveOrInAStoreNotLiveWaitsForNothing() throws Exception {
        VoidFacts beforeGoLive = kitchen.voided(UUID.randomUUID(), "1", false,
                com.xavier.backend.inventory.InventoryFixtures.ACTIVE_FROM.minusSeconds(1));
        UUID notLive = fixtures.newStore();
        VoidFacts elsewhere = new VoidFacts(UUID.randomUUID(), UUID.randomUUID(), notLive, Kitchen.DAY,
                clock.instant(), beforeGoLive.lineSnapshot(), BigDecimal.ONE, beforeGoLive.gross(), "error", false,
                UUID.randomUUID(), null, false, UUID.randomUUID());
        org.mockito.Mockito.when(orders.voidFacts(elsewhere.voidId())).thenReturn(elsewhere);

        voids.reverse(beforeGoLive.voidId());
        voids.reverse(elsewhere.voidId());

        assertThat(jdbc.sql("SELECT count(*) FROM inventory.line_void WHERE void_id IN (?, ?)")
                .params(beforeGoLive.voidId(), elsewhere.voidId()).query(Long.class).single()).isZero();
    }

    @Test
    void aVoidedMenuReversesEachComponentOnce() throws Exception {
        UUID menu = kitchen.catalogItem(null);
        UUID drink = kitchen.catalogItem(null);
        UUID juice = kitchen.stockItem("ml");
        kitchen.saleRecipe(drink, null, "1", ingredient(juice, "200", "ml"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN,
                Kitchen.menu(menu, "2", component(pizza, "1", "KITCHEN"), component(drink, "1", "BAR")));
        consumption.consume(round.batchId());
        VoidFacts one = kitchen.voided(lineOf(round), "1", true, clock.instant());

        voids.reverse(one.voidId());
        voids.reverse(one.voidId());

        assertThat(kitchen.quantity(kitchen.kitchen, flour)).isEqualByComparingTo("9.5");
        assertThat(kitchen.quantity(kitchen.bar, juice)).isEqualByComparingTo("-400");
        assertThat(kitchen.wasteRecords(one.voidId())).hasSize(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE source_type = 'VOID_COMPONENT' AND source_line_id = ?""").param(lineOf(round))
                .query(Long.class).single()).isZero();
    }

    private BatchFacts sold(String quantity) {
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pizza, quantity, "KITCHEN"));
        consumption.consume(round.batchId());
        return round;
    }

    private static UUID lineOf(BatchFacts round) {
        return round.lines().getFirst().lineId();
    }
}
