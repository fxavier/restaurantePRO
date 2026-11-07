package com.xavier.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What reporting pulls (D100, D131): the movements of one source in posting order, and the sources of a store's
 * business days for a rebuild; the waste of wasted voids and the cost variances of corrections and linked returns,
 * by source and by business day; the round a consumed line was settled from (S53). Read-only: reporting's facade
 * rule (S53) requires it.
 */
@InventoryModuleTest
class InventoryFactsTest {

    static final LocalDate MONDAY = LocalDate.parse("2026-11-02");
    static final LocalDate TUESDAY = MONDAY.plusDays(1);
    static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");

    @Autowired
    InventoryFacts facts;

    @Autowired
    StockLedger ledger;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    UUID store;
    UUID warehouse;
    UUID item;

    @BeforeEach
    void aWarehouse() throws Exception {
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
    }

    @Test
    void theMovementsOfASourceComeInPostingOrderWithTheirValues() {
        UUID line = UUID.randomUUID();
        UUID saleItem = UUID.randomUUID();
        post(PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal("4"), "GOODS_RECEIPT",
                UUID.randomUUID(), MONDAY, AT).atCost(new BigDecimal("2.5")));
        UUID secondItem = newItem();
        post(PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, new BigDecimal("-1"), "ORDER_LINE", line,
                        TUESDAY, AT).fromSale(saleItem, null, null),
                PostMovement.of(warehouse, secondItem, MovementType.SALE_CONSUMPTION, new BigDecimal("-2"),
                        "ORDER_LINE", line, TUESDAY, AT).fromSale(saleItem, null, null));

        MovementSet set = facts.movementsOf("ORDER_LINE", line).orElseThrow();

        assertThat(set.sourceType()).isEqualTo("ORDER_LINE");
        assertThat(set.movements()).extracting(MovementFact::stockItemId).containsExactly(item, secondItem);
        assertThat(set.movements().getFirst()).satisfies(fact -> {
            assertThat(fact.type()).isEqualTo("SALE_CONSUMPTION");
            assertThat(fact.quantity()).isEqualByComparingTo("-1");
            assertThat(fact.unitCost()).isEqualByComparingTo("2.5");
            assertThat(fact.value()).isEqualByComparingTo("-2.5");
            assertThat(fact.currency()).isEqualTo("MZN");
            assertThat(fact.storeId()).isEqualTo(store);
            assertThat(fact.warehouseId()).isEqualTo(warehouse);
            assertThat(fact.saleItemId()).isEqualTo(saleItem);
            assertThat(fact.businessDate()).isEqualTo(TUESDAY);
        });
        assertThat(set.movements().get(0).postingSeq()).isLessThan(set.movements().get(1).postingSeq());
        assertThat(facts.movementsOf("ORDER_LINE", UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSourcesOfAStoresBusinessDaysArePagedInPostingOrder() {
        UUID monday = UUID.randomUUID();
        UUID tuesdayFirst = UUID.randomUUID();
        UUID tuesdaySecond = UUID.randomUUID();
        post(in(monday, MONDAY));
        post(in(tuesdayFirst, TUESDAY));
        post(in(tuesdaySecond, TUESDAY));
        post(PostMovement.of(warehouse, item, MovementType.WASTE, BigDecimal.ONE.negate(), "MANUAL_MOVEMENT",
                tuesdayFirst, TUESDAY, AT));
        UUID elsewhere = fixtures.newWarehouseQuietly();
        post(PostMovement.of(elsewhere, item, MovementType.RETURN_TO_STOCK, BigDecimal.ONE, "MANUAL_MOVEMENT",
                UUID.randomUUID(), TUESDAY, AT));

        Page<SourceRef> first = facts.sourceIds(store, TUESDAY, TUESDAY, PageRequest.of(0, 1));
        Page<SourceRef> second = facts.sourceIds(store, TUESDAY, TUESDAY, PageRequest.of(1, 1));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).containsExactly(new SourceRef("MANUAL_MOVEMENT", tuesdayFirst));
        assertThat(second.getContent()).containsExactly(new SourceRef("MANUAL_MOVEMENT", tuesdaySecond));
        assertThat(facts.sourceIds(store, MONDAY, TUESDAY, PageRequest.of(0, 10)).getContent())
                .extracting(SourceRef::sourceId).containsExactly(monday, tuesdayFirst, tuesdaySecond);
    }

    @Test
    void theWastedVoidsOfAStoresBusinessDaysArePagedInRecordingOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        waste(first, MONDAY, "2026-11-03T10:00:00Z");
        waste(first, MONDAY, "2026-11-03T10:00:01Z"); // a second sale movement of the same void
        waste(second, MONDAY, "2026-11-03T10:00:02Z");
        waste(UUID.randomUUID(), TUESDAY, "2026-11-03T10:00:03Z");

        Page<UUID> page = facts.wasteVoidIds(store, MONDAY, MONDAY, PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).containsExactly(first);
        assertThat(facts.wasteVoidIds(store, MONDAY, MONDAY, PageRequest.of(1, 1)).getContent())
                .containsExactly(second);
        assertThat(facts.wasteVoidIds(store, MONDAY, TUESDAY, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
        assertThat(facts.wasteVoidIds(UUID.randomUUID(), MONDAY, TUESDAY, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void anOpenExceptionCountsForItsOrdersBusinessDayEvenWhenConfirmedAfterTheCutoff() {
        // Africa/Maputo (UTC+2), cutoff 05:00: Monday runs from 2026-11-02T03:00Z to 2026-11-03T03:00Z. An order
        // opened at 04:50 on Tuesday morning (Monday's day) whose round is confirmed at 05:05 (by its instant,
        // Tuesday) belongs to Monday.
        exception(MONDAY, "2026-11-03T03:05:00Z", false);
        exception(TUESDAY, "2026-11-03T10:00:00Z", false);
        exception(MONDAY, "2026-11-02T10:00:00Z", true); // resolved

        assertThat(facts.openExceptionCount(store, MONDAY, MONDAY)).isEqualTo(1);
        assertThat(facts.openExceptionCount(store, TUESDAY, TUESDAY)).as("the late round is Monday's").isEqualTo(1);
        assertThat(facts.openExceptionCount(store, MONDAY, TUESDAY)).isEqualTo(2);
        assertThat(facts.openExceptionCount(UUID.randomUUID(), MONDAY, TUESDAY)).isZero();
    }

    @Test
    void anExceptionWithoutItsDayCountsConservativelyByItsConfirmation() {
        exception(null, "2026-11-02T10:00:00Z", false); // Monday
        exception(null, "2026-11-03T10:00:00Z", false); // Tuesday by its instant, perhaps Monday's order
        exception(null, "2026-11-04T10:00:00Z", false); // Wednesday

        assertThat(facts.openExceptionCount(store, MONDAY, MONDAY)).as("Monday or the day after").isEqualTo(2);
        assertThat(facts.openExceptionCount(store, TUESDAY, TUESDAY)).isEqualTo(2);
        assertThat(facts.openExceptionCount(store, MONDAY.minusDays(1), MONDAY.minusDays(1))).isEqualTo(1);
    }

    private void exception(LocalDate businessDay, String confirmedAt, boolean resolved) {
        jdbc.sql("""
                        INSERT INTO inventory.consumption_exception (id, store_id, source_type, source_id, kind,
                            created_at, confirmed_at, business_day, resolved_at, resolution)
                        VALUES (?, ?, 'ORDER_LINE', ?, 'NO_RECIPE', now(), ?::timestamptz, ?,
                                CASE WHEN ? THEN now() END, CASE WHEN ? THEN 'reprocessed' END)""")
                .params(UUID.randomUUID(), store, UUID.randomUUID(), confirmedAt, businessDay, resolved, resolved)
                .update();
    }

    @Test
    void theRoundOfAConsumedLineIsKnownOnceConsumptionSettledIt() {
        UUID line = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.consumed_line (line_id, batch_id, store_id, quantity, confirmed_at)
                        VALUES (?, ?, ?, 2, now())""").params(line, batch, store).update();

        assertThat(facts.roundOf(line)).contains(batch);
        assertThat(facts.roundOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theCostVariancesOfACorrectionAndOfALinkedReturnAreReadBySourceAndListedByDay() throws Exception {
        UUID live = fixtures.newActiveStore();
        UUID room = fixtures.newWarehouse(live, "ALLOW");
        UUID flour = fixtures.newStockItem("un");
        PurchasingSteps steps = new PurchasingSteps(mvc, jdbc);
        UUID supplier = steps.newSupplier();
        UUID receipt = steps.confirmedReceipt(live, room, supplier, flour, "10", "10");
        String line = steps.get(live, "/receipts/" + receipt).get("lines").get(0).get("id").asString();
        steps.waste(live, room, flour, "4");
        UUID correction = InventoryFixtures.id(mvc.perform(InventoryFixtures.idem(MockMvcRequestBuilders.post(InventoryFixtures.BO
                                + "/receipts/" + receipt + "/lines/" + line + "/cost-corrections"))
                        .with(PurchasingSteps.buyer(live)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUnitCost\": 12, \"reason\": \"invoice\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.confirmedReceipt(live, room, supplier, flour, "6", "24"); // 12 on hand at an average of 18
        UUID returned = InventoryFixtures.id(mvc.perform(InventoryFixtures.idem(MockMvcRequestBuilders.post(InventoryFixtures.BO
                                + "/supplier-returns")).with(PurchasingSteps.buyer(live))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "goodsReceiptId": "%s", "reason": "damaged",
                                 "lines": [{"receiptLineId": "%s", "stockItemId": "%s", "quantity": 2}]}"""
                                .formatted(room, receipt, line, flour)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.command(live, "/supplier-returns/" + returned + "/confirm").andExpect(status().isOk());

        CostVarianceFact corrected = facts.costVariancesOf("COST_CORRECTION", correction).getFirst();
        assertThat(corrected.value()).isEqualByComparingTo("8"); // the consumed 4 x (12 - 10)
        assertThat(corrected.storeId()).isEqualTo(live);
        assertThat(corrected.warehouseId()).isEqualTo(room);
        assertThat(corrected.stockItemId()).isEqualTo(flour);
        assertThat(corrected.receiptLineId()).hasToString(line);
        assertThat(corrected.currency()).isEqualTo("MZN");
        assertThat(corrected.sourceType()).isEqualTo("COST_CORRECTION");
        assertThat(corrected.sourceId()).isEqualTo(correction);
        List<CostVarianceFact> ofReturn = facts.costVariancesOf("SUPPLIER_RETURN", returned);
        assertThat(ofReturn).singleElement().satisfies(fact -> {
            assertThat(fact.value()).isEqualByComparingTo("12"); // (average 18 - line cost 12) x 2
            assertThat(fact.sourceId()).isEqualTo(returned);
            assertThat(fact.businessDate()).isEqualTo(corrected.businessDate());
        });
        assertThat(facts.costVariancesOf("SUPPLIER_RETURN", correction)).isEmpty();
        assertThat(facts.costVariancesOf("GOODS_RECEIPT", receipt)).isEmpty();

        LocalDate day = corrected.businessDate();
        assertThat(facts.costVarianceSources(live, day, day, PageRequest.of(0, 10)).getContent()).containsExactly(
                new SourceRef("COST_CORRECTION", correction), new SourceRef("SUPPLIER_RETURN", returned));
        assertThat(facts.costVarianceSources(live, day, day, PageRequest.of(1, 1)).getContent())
                .containsExactly(new SourceRef("SUPPLIER_RETURN", returned));
        assertThat(facts.costVarianceSources(live, day.plusDays(1), day.plusDays(1), PageRequest.of(0, 10)))
                .isEmpty();
    }

    private void waste(UUID voidId, LocalDate day, String createdAt) {
        jdbc.sql("""
                        INSERT INTO inventory.waste_record (id, void_id, original_movement_id, store_id, warehouse_id,
                            stock_item_id, source_line_id, quantity, unit_cost, value, currency, business_date,
                            occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, 2, 2, 'MZN', ?, now(), ?::timestamptz)""")
                .params(UUID.randomUUID(), voidId, UUID.randomUUID(), store, warehouse, item, UUID.randomUUID(), day,
                        createdAt).update();
    }

    private PostMovement in(UUID source, LocalDate day) {
        return PostMovement.of(warehouse, item, MovementType.RETURN_TO_STOCK, BigDecimal.ONE, "MANUAL_MOVEMENT",
                source, day, AT);
    }

    private void post(PostMovement... movements) {
        tx.executeWithoutResult(status -> ledger.post(List.of(movements)));
    }

    private UUID newItem() {
        try {
            return fixtures.newStockItem("un");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
