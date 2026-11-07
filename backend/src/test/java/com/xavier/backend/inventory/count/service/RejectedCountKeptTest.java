package com.xavier.backend.inventory.count.service;

import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.StockLedger;
import java.time.Clock;
import java.time.Instant;
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
 * D119 {@code RejectedCountKeptTest}: rejecting needs {@code INVENTORY_COUNT_APPROVE} and a reason; the rejected
 * count is kept with its lines, posts nothing, frees the warehouse for a new count, and the database refuses any
 * later change to it or its lines.
 */
@InventoryModuleTest
class RejectedCountKeptTest {

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
    UUID item;
    UUID count;

    @BeforeEach
    void submittedCount() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        counts.receipt(warehouse, item, "10", "2", now.minusSeconds(3600));
        count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, item, "6", now, null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
    }

    @Test
    void aRejectedCountIsKeptPostsNothingAndFreesTheWarehouse() throws Exception {
        reject("{\"reason\": \"recount\"}", Permission.INVENTORY_COUNT).andExpect(status().isForbidden());
        reject("{\"reason\": \" \"}", Permission.INVENTORY_COUNT_APPROVE).andExpect(status().isBadRequest());

        reject("{\"reason\": \"the bar shelf was missed\"}", Permission.INVENTORY_COUNT_APPROVE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("the bar shelf was missed"));

        mvc.perform(get(CountFixtures.COUNTS + "/" + count).with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.lines[0].countedQuantity").value(6.0))
                .andExpect(jsonPath("$.lines[0].expectedQuantity").doesNotExist());
        assertThat(counts.movementsOf(count)).isEmpty();
        assertThat(counts.balance(warehouse, item)).isEqualTo("10.000000|20.0000");
        counts.approve(store, count).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUNT_STATE"));
        counts.create(store, warehouse, "REGULAR").andExpect(status().isCreated());
    }

    @Test
    void theDatabaseKeepsADecidedCountAndSubmittedLinesAsTheyAre() throws Exception {
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_count_line SET counted_quantity = 9 WHERE count_id = ?")
                .param(count).update()).hasMessageContaining("immutable");
        reject("{\"reason\": \"recount\"}", Permission.INVENTORY_COUNT_APPROVE).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_count SET reject_reason = 'other' WHERE id = ?")
                .param(count).update()).hasMessageContaining("final");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM inventory.stock_count_line WHERE count_id = ?")
                .param(count).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_count_line SET expected_quantity = 6, difference = 0"
                + " WHERE count_id = ?").param(count).update()).hasMessageContaining("immutable");
    }

    private org.springframework.test.web.servlet.ResultActions reject(String body, Permission permission)
            throws Exception {
        return mvc.perform(idem(post(CountFixtures.COUNTS + "/" + count + "/reject")).with(account(store, permission))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
