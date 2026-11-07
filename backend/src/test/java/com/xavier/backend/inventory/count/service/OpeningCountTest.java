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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * D122/D119 {@code OpeningCountTest}: a store goes live with an opening count. Before it, manual movements and
 * regular counts are refused (422 INVENTORY_NOT_ACTIVE); approving it posts OPENING movements at the entered cost at
 * each cut-off and activates the store. An opening is the warehouse's first fact: a second one, or one with a
 * movement at or before its cut-off, is 422 OPENING_NOT_FIRST; a movement after the cut-off is not in its way.
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
    Clock clock;

    @Autowired
    ActivationService activations;

    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID flour;
    Instant now;

    @BeforeEach
    void aStoreNotLiveYet() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc);
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("un");
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void theOpeningCountPostsTheOpeningStockAtItsCostAndMakesTheStoreLive() throws Exception {
        waste().andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));
        counts.create(store, warehouse, "REGULAR").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));

        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, opening, flour, "10", ago(10), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_COST_REQUIRED"));
        counts.line(store, opening, flour, "10", ago(10), "2.5").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        counts.approve(store, opening).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].expectedQuantity").value(0.0))
                .andExpect(jsonPath("$.lines[0].difference").value(10.0));

        assertThat(counts.movementsOf(opening)).containsExactly(
                flour + " OPENING 10.000000 2.500000 " + ago(10).toString().replace("Z", ""));
        assertThat(counts.balance(warehouse, flour)).isEqualTo("10.000000|25.0000");
        assertThat(activations.activeFrom(store)).contains(clock.instant());
        waste().andExpect(status().isCreated());
        counts.create(store, warehouse, "REGULAR").andExpect(status().isCreated());
    }

    @Test
    void aWarehouseHasOneOpening() throws Exception {
        approvedOpening(warehouse, ago(30));
        UUID again = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, again, flour, "4", now, "1").andExpect(status().isOk());
        counts.command(store, again, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, again).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_NOT_FIRST"));

        assertThat(counts.movementsOf(again)).isEmpty();
        assertThat(counts.balance(warehouse, flour)).isEqualTo("10.000000|25.0000");
    }

    @Test
    void anOpeningAfterAMovementIsRefusedAndTheStoreStaysNotLive() throws Exception {
        counts.receipt(warehouse, flour, "3", "1", ago(60)); // e.g. a transfer in before go-live
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, opening, flour, "10", ago(10), "2.5").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, opening).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPENING_NOT_FIRST"));

        assertThat(counts.movementsOf(opening)).isEmpty();
        assertThat(activations.activeFrom(store)).isEmpty();
    }

    @Test
    void aSaleAfterTheOpeningCutOffIsNotInItsWay() throws Exception {
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, opening, flour, "10", ago(10), "2.5").andExpect(status().isOk());
        counts.sale(warehouse, flour, "1", ago(5));
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, opening).andExpect(status().isOk());

        assertThat(counts.balance(warehouse, flour)).isEqualTo("9.000000|22.5000");
    }

    private void approvedOpening(UUID warehouse, Instant countedAt) throws Exception {
        UUID opening = counts.startedCount(store, warehouse, "OPENING");
        counts.line(store, opening, flour, "10", countedAt, "2.5").andExpect(status().isOk());
        counts.command(store, opening, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        counts.approve(store, opening).andExpect(status().isOk());
    }

    private ResultActions waste() throws Exception {
        return mvc.perform(idem(post(BO + "/movements/waste")).with(account(store, Permission.INVENTORY_MOVE))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "reason": "dropped",
                         "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(warehouse, flour)));
    }

    private Instant ago(long minutes) {
        return now.minus(Duration.ofMinutes(minutes));
    }
}
