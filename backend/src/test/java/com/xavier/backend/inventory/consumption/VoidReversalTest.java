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
@org.springframework.test.context.event.RecordApplicationEvents
class VoidReversalTest extends ConsumptionTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    com.xavier.backend.inventory.InventoryFacts facts;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.test.context.event.ApplicationEvents published;

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
        assertThat(kitchen.rows(kitchen.report("waste"), flour)).singleElement().satisfies(row -> {
            assertThat(row.get("quantity").decimalValue()).isEqualByComparingTo("0.5");
            assertThat(row.get("value").decimalValue()).isEqualByComparingTo("1.0000");
        });
        voids.reverse(dropped.voidId());
        assertThat(kitchen.wasteRecords(dropped.voidId())).hasSize(1);
    }

    /**
     * A wasted kilo is reclassified from sale to waste: the rotation report counts no consumption for it, the waste
     * report counts it once, and a ConsumptionWasteRecorded event carries it (no stock movement fires).
     */
    @Test
    void aWastedKiloCountsOnceAsWasteAndNotAsConsumption() throws Exception {
        UUID sugar = kitchen.stockItem("kg");
        UUID cake = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, sugar, "10", "2.000000");
        kitchen.saleRecipe(cake, null, "1", ingredient(sugar, "1", "kg"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(cake, "1", "KITCHEN"));
        consumption.consume(round.batchId());

        VoidFacts dropped = kitchen.voided(lineOf(round), "1", true, clock.instant());
        voids.reverse(dropped.voidId());

        assertThat(kitchen.rows(kitchen.report("rotation"), sugar)).singleElement()
                .satisfies(row -> assertThat(row.get("consumptionCost").decimalValue()).isEqualByComparingTo("0"));
        assertThat(kitchen.rows(kitchen.report("waste"), sugar)).singleElement().satisfies(row -> {
            assertThat(row.get("quantity").decimalValue()).isEqualByComparingTo("1");
            assertThat(row.get("value").decimalValue()).isEqualByComparingTo("2.0000");
        });
        assertThat(facts.wasteOf(dropped.voidId())).singleElement().satisfies(waste -> {
            assertThat(waste.stockItemId()).isEqualTo(sugar);
            assertThat(waste.quantity()).isEqualByComparingTo("1");
            assertThat(waste.value()).isEqualByComparingTo("2.0000");
        });
        assertThat(published.stream(com.xavier.backend.inventory.model.event.ConsumptionWasteRecorded.class)
                .filter(event -> event.voidId().equals(dropped.voidId()))).singleElement().satisfies(event -> {
            assertThat(event.storeId()).isEqualTo(kitchen.store);
            assertThat(event.warehouseId()).isEqualTo(kitchen.kitchen);
            assertThat(event.stockItemId()).isEqualTo(sugar);
            assertThat(event.quantity()).isEqualByComparingTo("1");
            assertThat(event.value()).isEqualByComparingTo("2.0000");
            assertThat(event.occurredAt()).isEqualTo(dropped.at());
        });
    }

    /** Two half voids put back the sale's whole value: the last one carries what the first left (review repro). */
    @Test
    void twoHalfVoidsPutBackTheWholeValueOfTheSale() throws Exception {
        UUID sugar = kitchen.stockItem("kg");
        UUID cake = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, sugar, "10", "1.000050");
        kitchen.saleRecipe(cake, null, "1", ingredient(sugar, "0.5", "kg"));
        BigDecimal before = kitchen.value(kitchen.kitchen, sugar);
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(cake, "2", "KITCHEN"));
        consumption.consume(round.batchId());

        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());

        assertThat(kitchen.quantity(kitchen.kitchen, sugar)).isEqualByComparingTo("10");
        assertThat(kitchen.value(kitchen.kitchen, sugar)).isEqualByComparingTo(before).isEqualByComparingTo("10.0005");
    }

    /** A sale that emptied the stock took its whole value; voiding it in full puts exactly that value back. */
    @Test
    void aFullVoidOfASaleThatEmptiedTheStockPutsItsWholeValueBack() throws Exception {
        UUID cream = kitchen.stockItem("g");
        UUID cake = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, cream, "1000", "0.000333");
        kitchen.receive(kitchen.kitchen, cream, "2000", "0.000334"); // 3000 g worth 1.0010, average 0.000334
        kitchen.saleRecipe(cake, null, "1", ingredient(cream, "3000", "g"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(cake, "1", "KITCHEN"));
        consumption.consume(round.batchId());
        assertThat(kitchen.value(kitchen.kitchen, cream)).isEqualByComparingTo("0");

        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());

        assertThat(kitchen.quantity(kitchen.kitchen, cream)).isEqualByComparingTo("3000");
        assertThat(kitchen.value(kitchen.kitchen, cream)).isEqualByComparingTo("1.0010");
    }

    /**
     * Check (review item 9): a line split three ways at 6 decimals (S46) and every copy voided in full. The voids
     * name the same round line and add up to its quantity, so the line nets to zero in quantity and value.
     */
    @Test
    void aLineSplitThreeWaysAndVoidedInFullNetsToZero() throws Exception {
        UUID salt = kitchen.stockItem("kg");
        UUID pinch = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, salt, "1", "3.000000");
        kitchen.saleRecipe(pinch, null, "1", ingredient(salt, "0.1", "kg"));
        BigDecimal before = kitchen.value(kitchen.kitchen, salt);
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pinch, "1", "KITCHEN"));
        consumption.consume(round.batchId());

        for (String copy : java.util.List.of("0.333333", "0.333333", "0.333334")) {
            voids.reverse(kitchen.voided(lineOf(round), copy, false, clock.instant()).voidId());
        }

        assertThat(kitchen.quantity(kitchen.kitchen, salt)).isEqualByComparingTo("1");
        assertThat(kitchen.value(kitchen.kitchen, salt)).isEqualByComparingTo(before);
    }

    /**
     * Review repro (salt bag): 300 sales of 1 g from a 300 g bag at 0.012355 round each sale up; the stock value never
     * goes below zero, so the late sales carry less than nominal, and voiding the last one never puts back a negative
     * value.
     */
    @Test
    void aBagSoldGramByGramNeverGoesBelowZeroInValueAndItsVoidIsNotNegative() throws Exception {
        UUID salt = kitchen.stockItem("g");
        UUID dish = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, salt, "300", "0.012355"); // 3.7065
        kitchen.saleRecipe(dish, null, "1", ingredient(salt, "1", "g"));
        BatchFacts round = null;
        for (int i = 0; i < 300; i++) {
            round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(dish, "1", "KITCHEN"));
            consumption.consume(round.batchId());
        }
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_item_id = ? AND type = 'SALE_CONSUMPTION' AND value > 0""").param(salt)
                .query(Long.class).single()).isZero();
        assertThat(kitchen.value(kitchen.kitchen, salt)).isEqualByComparingTo("0");

        VoidFacts error = kitchen.voided(lineOf(round), "1", false, clock.instant());
        voids.reverse(error.voidId());

        assertThat((BigDecimal) kitchen.movements("VOID", error.voidId()).getFirst().get("value")).isNotNegative();
        assertThat(kitchen.value(kitchen.kitchen, salt)).isNotNegative();
    }

    /** Review repro: a sale that emptied the stock, voided in ten parts; no part puts back a negative value. */
    @Test
    void tenVoidsOfAnEmptyingSaleNeverPutBackANegativeValue() throws Exception {
        BatchFacts round = emptyingIceSale();
        for (int i = 0; i < 10; i++) {
            voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        }

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE stock_item_id = ? AND type = 'CONSUMPTION_REVERSAL' AND value < 0""").param(ice)
                .query(Long.class).single()).isZero();
        assertThat(kitchen.quantity(kitchen.kitchen, ice)).isEqualByComparingTo("3000");
        assertThat(kitchen.value(kitchen.kitchen, ice)).isEqualByComparingTo("0.0050");
    }

    /** Review repro: the same, the last part wasted: its waste record is never negative (waste_record_cost_ck). */
    @Test
    void aWastedLastPartOfAnEmptyingSaleRecordsNoNegativeWaste() throws Exception {
        BatchFacts round = emptyingIceSale();
        for (int i = 0; i < 9; i++) {
            voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        }
        VoidFacts wasted = kitchen.voided(lineOf(round), "1", true, clock.instant());

        voids.reverse(wasted.voidId());

        assertThat(kitchen.wasteRecords(wasted.voidId())).singleElement()
                .satisfies(w -> assertThat((BigDecimal) w.get("value")).isNotNegative());
    }

    /** M8: a wasted void, then an unwasted one, on a 2-unit line: each settles half (the waste counts as settled). */
    @Test
    void aWastedThenAnUnwastedVoidEachSettleTheirShare() throws Exception {
        BatchFacts round = sold("2");
        voids.reverse(kitchen.voided(lineOf(round), "1", true, clock.instant()).voidId());
        VoidFacts error = kitchen.voided(lineOf(round), "1", false, clock.instant());

        voids.reverse(error.voidId());

        assertThat((BigDecimal) kitchen.movements("VOID", error.voidId()).getFirst().get("quantity"))
                .isEqualByComparingTo("0.25");
    }

    /** M10: a wasted void that completes the line records exactly the remainder rounding left. */
    @Test
    void aWastedVoidThatCompletesTheLineRecordsTheExactRemainder() throws Exception {
        UUID salt = kitchen.stockItem("kg");
        UUID pinch = kitchen.catalogItem(null);
        kitchen.saleRecipe(pinch, null, "3", ingredient(salt, "0.01", "g")); // 3 sold → 0.000010 kg
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(pinch, "3", "KITCHEN"));
        consumption.consume(round.batchId());
        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        voids.reverse(kitchen.voided(lineOf(round), "1", false, clock.instant()).voidId());
        VoidFacts last = kitchen.voided(lineOf(round), "1", true, clock.instant());

        voids.reverse(last.voidId());

        assertThat(kitchen.wasteRecords(last.voidId())).singleElement()
                .satisfies(w -> assertThat((BigDecimal) w.get("quantity")).isEqualByComparingTo("0.000004"));
    }

    /** M11: a redelivered wasted void publishes its ConsumptionWasteRecorded event once. */
    @Test
    void aRedeliveredWastedVoidPublishesItsEventOnce() throws Exception {
        BatchFacts round = sold("2");
        VoidFacts dropped = kitchen.voided(lineOf(round), "1", true, clock.instant());

        voids.reverse(dropped.voidId());
        voids.reverse(dropped.voidId());

        assertThat(published.stream(com.xavier.backend.inventory.model.event.ConsumptionWasteRecorded.class)
                .filter(event -> event.voidId().equals(dropped.voidId()))).hasSize(1);
    }

    private UUID ice;

    /** 3000 ml worth 0.0050 (average 0.000002), all sold in one line of 10 drinks: the sale takes the whole value. */
    private BatchFacts emptyingIceSale() throws Exception {
        ice = kitchen.stockItem("ml");
        UUID drink = kitchen.catalogItem(null);
        kitchen.receive(kitchen.kitchen, ice, "1000", "0.000001");
        kitchen.receive(kitchen.kitchen, ice, "2000", "0.000002");
        kitchen.saleRecipe(drink, null, "1", ingredient(ice, "300", "ml"));
        BatchFacts round = kitchen.round(clock.instant(), OrderMode.DINE_IN, line(drink, "10", "KITCHEN"));
        consumption.consume(round.batchId());
        return round;
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
                kitchen.liveFrom().minusSeconds(1));
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
