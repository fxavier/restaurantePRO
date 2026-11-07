package com.xavier.backend.inventory.count.controller;

import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.StockLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The counts HTTP contract (PRD R4 S49): permissions per command in the count's store (D42: 404 before 403), one
 * live count per warehouse, lines in the base unit and only while IN_PROGRESS, LIST scope, and the divergences
 * report.
 */
@InventoryModuleTest
class StockCountHttpTest {

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

    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID flour;
    Instant now;

    @BeforeEach
    void liveStore() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("g");
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void creatingACountNeedsInventoryCountInTheWarehouseStore() throws Exception {
        create(account(UUID.randomUUID(), Permission.INVENTORY_COUNT), warehouse).andExpect(status().isForbidden());
        create(account(store, Permission.INVENTORY_VIEW), warehouse).andExpect(status().isForbidden());
        create(account(null, Permission.INVENTORY_COUNT), UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void aWarehouseHasOneLiveCount() throws Exception {
        counts.create(store, warehouse, "REGULAR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        counts.create(store, warehouse, "REGULAR").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIVE_COUNT_EXISTS"));
    }

    @Test
    void aLineIsKeptInTheBaseUnitAtNowByDefaultAndFrozenBySubmit() throws Exception {
        UUID count = counts.startedCount(store, warehouse, "REGULAR");

        mvc.perform(put(CountFixtures.COUNTS + "/" + count + "/lines/" + flour).with(csrf())
                        .with(account(store, Permission.INVENTORY_COUNT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"countedQuantity\": 1.5, \"unit\": \"kg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].countedQuantity").value(1500.0))
                .andExpect(jsonPath("$.lines[0].unit").value("g"))
                .andExpect(jsonPath("$.lines[0].countedAt").value(clock.instant().toString()));
        counts.line(store, count, flour, "1", now.plusSeconds(3600), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COUNTED_AT_IN_FUTURE"));
        counts.line(store, count, flour, "1", now, "2").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_COST_OPENING_ONLY"));
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.line(store, count, flour, "2", now, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUNT_STATE"));
        counts.command(store, count, "cancel", Permission.INVENTORY_COUNT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUNT_STATE"));
    }

    @Test
    void approvingNeedsInventoryCountApproveAndAnUnknownCountIsNotFoundFirst() throws Exception {
        UUID count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, flour, "1", now, null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.command(store, count, "approve", Permission.INVENTORY_COUNT).andExpect(status().isForbidden());
        counts.command(UUID.randomUUID(), count, "approve", Permission.INVENTORY_COUNT_APPROVE)
                .andExpect(status().isForbidden());
        counts.command(UUID.randomUUID(), UUID.randomUUID(), "approve", Permission.INVENTORY_COUNT_APPROVE)
                .andExpect(status().isNotFound());
        mvc.perform(get(CountFixtures.COUNTS + "/" + count).with(account(UUID.randomUUID(), Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aListCountTakesOnlyItsItemsAndAllOfThem() throws Exception {
        UUID oil = fixtures.newStockItem("ml");
        UUID other = fixtures.newStockItem("un");
        String body = mvc.perform(idem(post(CountFixtures.COUNTS)).with(account(store, Permission.INVENTORY_COUNT))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "kind": "REGULAR", "scope": "LIST", "stockItemIds": ["%s", "%s"]}"""
                                .formatted(warehouse, flour, oil)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID count = InventoryFixtures.id(body);
        counts.command(store, count, "start", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.line(store, count, other, "1", now, null).andExpect(status().isNotFound());
        counts.line(store, count, flour, "1", now, null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COUNT_INCOMPLETE"));
    }

    @Test
    void theDivergencesReportListsTheAdjustmentsOfApprovedCounts() throws Exception {
        counts.receipt(warehouse, flour, "10", "2", now.minusSeconds(3600));
        UUID count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, flour, "9", now, null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        counts.approve(store, count).andExpect(status().isOk());
        LocalDate day = LocalDate.ofInstant(now.minusSeconds(5 * 3600), ZoneId.of("Africa/Maputo"));

        mvc.perform(get("/api/v1/backoffice/inventory/reports/count-divergences")
                        .param("storeId", store.toString()).param("from", day.minusDays(1).toString())
                        .param("to", day.plusDays(1).toString()).with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].countId").value(count.toString()))
                .andExpect(jsonPath("$.rows[0].expectedQuantity").value(10.0))
                .andExpect(jsonPath("$.rows[0].countedQuantity").value(9.0))
                .andExpect(jsonPath("$.rows[0].difference").value(-1.0))
                .andExpect(jsonPath("$.rows[0].value").value(-2.0))
                .andExpect(jsonPath("$.totalValue").value(-2.0));
        mvc.perform(get("/api/v1/backoffice/inventory/reports/count-divergences")
                        .param("storeId", store.toString()).param("from", day.toString())
                        .param("to", day.minusDays(1).toString()).with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
            org.springframework.test.web.servlet.request.RequestPostProcessor actor, UUID warehouseId)
            throws Exception {
        return mvc.perform(idem(post(CountFixtures.COUNTS)).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\": \"%s\", \"kind\": \"REGULAR\", \"scope\": \"ALL\"}".formatted(warehouseId)));
    }
}
