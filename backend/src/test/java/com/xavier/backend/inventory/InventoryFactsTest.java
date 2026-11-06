package com.xavier.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What reporting pulls (D100, D131): the movements of one source in posting order, and the sources of a store's
 * business days for a rebuild. Read-only: reporting's facade rule (S53) requires it.
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
