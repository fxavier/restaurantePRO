package com.xavier.backend.inventory.count.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.organization.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D119 {@code CountCutoffTest}: each line is settled against what was expected at its own counted-at instant, so a
 * sale during the count is handled: one after a line's cut-off is not counted against it, one before is.
 */
@InventoryModuleTest
class CountCutoffTest {

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

    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID flour;
    UUID oil;
    Instant now;

    @BeforeEach
    void stock() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc, clock);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("un");
        oil = fixtures.newStockItem("un");
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        counts.receipt(warehouse, flour, "10", "2", ago(180));
        counts.receipt(warehouse, oil, "5", "4", ago(180));
    }

    @Test
    void eachLineIsSettledAtItsOwnCutOffAndALaterSaleIsNotCountedAgainstIt() throws Exception {
        counts.sale(warehouse, flour, "1", ago(120));
        UUID count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, flour, "7", counts.at(30), null).andExpect(status().isOk());
        counts.line(store, count, oil, "4", counts.at(90), null).andExpect(status().isOk());
        counts.sale(warehouse, flour, "2", counts.at(60)); // after flour's cut-off, before oil's
        counts.sale(warehouse, oil, "1", counts.at(60));   // before oil's cut-off: the 4 counted already miss it
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, count).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[?(@.stockItemId == '%s')].expectedQuantity".formatted(flour)).value(9.0))
                .andExpect(jsonPath("$.lines[?(@.stockItemId == '%s')].difference".formatted(flour)).value(-2.0))
                .andExpect(jsonPath("$.lines[?(@.stockItemId == '%s')].expectedQuantity".formatted(oil)).value(4.0))
                .andExpect(jsonPath("$.lines[?(@.stockItemId == '%s')].difference".formatted(oil)).value(0.0));

        assertThat(counts.movementsOf(count)).containsExactly(
                flour + " COUNT_ADJUSTMENT -2.000000 2.000000 " + counts.at(30).toString().replace("Z", ""));
        assertThat(counts.balance(warehouse, flour)).isEqualTo("5.000000|10.0000");
        assertThat(counts.balance(warehouse, oil)).isEqualTo("4.000000|16.0000");
    }

    private Instant ago(long minutes) {
        return now.minus(Duration.ofMinutes(minutes));
    }
}
