package com.xavier.backend.inventory.count.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.organization.MutableClock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D122/D119 as amended, {@code OpeningCountTest}: starting an opening count makes the store live
 * ({@code active_from = started_at}), so sales during the count are consumed live. Approval settles the opening like
 * any count, OPENING = counted − expected at each cut-off, at the entered cost, so book = shelf. An opening is the
 * warehouse's first fact: an earlier approved opening, or a non-consumption movement at or before its cut-off, is
 * 422 OPENING_NOT_FIRST; sales (and their voids) are not in its way.
 */
@InventoryModuleTest
class OpeningCountTest {

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

    @Autowired
    ActivationService activations;

    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID flour;

    @BeforeEach
    void aStoreNotLiveYet() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc, clock);
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("un");
    }

    @Test
    void startingTheOpeningMakesTheStoreLiveAndApprovalPostsTheOpeningStockAtItsCost() throws Exception {
        UUID bar = fixtures.newWarehouse(store, "ALLOW");
        waste(bar).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));
        counts.create(store, warehouse, "REGULAR").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));

        UUID opening = counts.startedCount(store, warehouse, "OPENING");

        assertThat(activations.activeFrom(store)).contains(counts.startedAt());
        waste(bar).andExpect(status().isCreated());
        counts.line(store, opening, flour, "10", counts.at(10), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_COST_REQUIRED"));
        counts.line(store, opening, flour, "10", counts.at(10), "2.5").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        counts.approve(store, opening).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].expectedQuantity").value(0.0))
                .andExpect(jsonPath("$.lines[0].difference").value(10.0));

        assertThat(counts.movementsOf(opening)).containsExactly(
                flour + " OPENING 10.000000 2.500000 " + plain(counts.at(10)));
        assertThat(counts.balance(warehouse, flour)).isEqualTo("10.000000|25.0000");
        assertThat(activations.activeFrom(store)).contains(counts.startedAt());
    }

    /** Tomatoes: 10 on the shelf at the count, 2 sold (and consumed) before the approval: the book says 8. */
    @Test
    void salesBetweenTheCountAndTheApprovalAreConsumedAndTheBookMatchesTheShelf() throws Exception {
        UUID tomato = fixtures.newStockItem("un");
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, opening, tomato, "10", counts.at(10), "1").andExpect(status().isOk());
        counts.sale(warehouse, tomato, "1", counts.at(30));
        counts.sale(warehouse, tomato, "1", counts.at(40));
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, opening).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].expectedQuantity").value(0.0))
                .andExpect(jsonPath("$.lines[0].difference").value(10.0));

        assertThat(counts.balance(warehouse, tomato)).isEqualTo("8.000000|8.0000");
    }

    /** Cheese: one sold before it was counted (expected −1), 5 counted: the opening brings in 6, the book is 5. */
    @Test
    void aSaleBeforeTheCutOffIsAbsorbedByTheOpening() throws Exception {
        UUID cheese = fixtures.newStockItem("un");
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.sale(warehouse, cheese, "1", counts.at(5));
        counts.line(store, opening, cheese, "5", counts.at(10), "2").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, opening).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].expectedQuantity").value(-1.0))
                .andExpect(jsonPath("$.lines[0].difference").value(6.0));

        // the sale went out at the average of an empty stock (0); the opening's REVALUATION moves it to its cost
        assertThat(counts.movementsOf(opening)).containsExactly(
                cheese + " OPENING 6.000000 2.000000 " + plain(counts.at(10)),
                cheese + " REVALUATION 0.000000 2.000000 " + plain(counts.at(10)));
        assertThat(counts.balance(warehouse, cheese)).isEqualTo("5.000000|10.0000");
    }

    @Test
    void aWarehouseHasOneOpening() throws Exception {
        UUID first = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, first, flour, "10", counts.at(10), "2.5").andExpect(status().isOk());
        counts.command(store, first, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        counts.approve(store, first).andExpect(status().isOk());

        UUID again = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, again, flour, "4", counts.at(10), "1").andExpect(status().isOk());
        counts.command(store, again, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, again).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_NOT_FIRST"));

        assertThat(counts.movementsOf(again)).isEmpty();
        assertThat(counts.balance(warehouse, flour)).isEqualTo("10.000000|25.0000");
    }

    @Test
    void anOpeningAfterANonConsumptionMovementIsRefused() throws Exception {
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.receipt(warehouse, flour, "3", "1", counts.at(5)); // a receipt before the cut-off
        counts.line(store, opening, flour, "10", counts.at(10), "2.5").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, opening).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_NOT_FIRST"));

        assertThat(counts.movementsOf(opening)).isEmpty();
        assertThat(counts.balance(warehouse, flour)).isEqualTo("3.000000|3.0000");
    }

    /** The store went live with the kitchen's opening; the bar sells before its own opening is counted. */
    @Test
    void aSecondWarehousesOpeningAfterGoLiveAbsorbsTheSalesBeforeItsCount() throws Exception {
        counts.startedCount(store, warehouse, "OPENING");
        Instant live = counts.startedAt();
        UUID bar = fixtures.newWarehouse(store, "ALLOW");
        UUID barOpening = counts.startedCount(store, bar, "OPENING");
        counts.sale(bar, flour, "1", counts.at(5));
        counts.line(store, barOpening, flour, "4", counts.at(10), "3").andExpect(status().isOk());
        counts.command(store, barOpening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, barOpening).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].difference").value(5.0));

        assertThat(counts.balance(bar, flour)).isEqualTo("4.000000|12.0000");
        assertThat(activations.activeFrom(store)).contains(live);
    }

    private ResultActions waste(UUID warehouseId) throws Exception {
        return mvc.perform(idem(post(BO + "/movements/waste")).with(account(store, Permission.INVENTORY_MOVE))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "reason": "dropped",
                         "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(warehouseId, flour)));
    }

    private static String plain(Instant at) {
        return at.toString().replace("Z", "");
    }
}
