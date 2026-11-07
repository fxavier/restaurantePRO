package com.xavier.backend.inventory.count.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.StockLedger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D119 {@code CountBacklogTest}: approval is refused (409 CONSUMPTION_BACKLOG) while a consumption publication is
 * pending, because a round confirmed before a cut-off may not be posted yet; once it completes, approval goes
 * through. S47's listeners are not merged, so the held listener is its registry row: an incomplete
 * {@code event_publication} of a listener in {@code com.xavier.backend.inventory.consumption} (what
 * {@code EventPublicationStats.pendingFor} counts, the PRD's check), completed to release it.
 */
@InventoryModuleTest
class CountBacklogTest {

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

    UUID heldPublication;

    @BeforeEach
    void aRoundIsBeingConsumed() {
        heldPublication = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                            status, completion_attempts)
                        VALUES (?, ?, 'com.xavier.backend.orders.model.event.OrderBatchConfirmed', '{}', ?,
                            'PROCESSING', 1)""")
                .params(heldPublication, "com.xavier.backend.inventory.consumption.RoundConsumptionListener"
                        + ".on(com.xavier.backend.orders.model.event.OrderBatchConfirmed)", Timestamp.from(Instant.now()))
                .update();
    }

    @AfterEach
    void release() {
        complete(heldPublication);
    }

    @Test
    void approvalWaitsForTheConsumptionBacklogAndGoesThroughOnceItIsProcessed() throws Exception {
        CountFixtures counts = new CountFixtures(mvc, ledger, tx, jdbc);
        UUID store = fixtures.newActiveStore();
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        UUID item = fixtures.newStockItem("un");
        counts.receipt(warehouse, item, "10", "2", clock.instant().minusSeconds(3600));
        UUID count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, item, "8", clock.instant(), null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());

        counts.approve(store, count).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSUMPTION_BACKLOG"));
        assertThat(counts.movementsOf(count)).isEmpty();
        assertThat(counts.balance(warehouse, item)).isEqualTo("10.000000|20.0000");

        complete(heldPublication);

        counts.approve(store, count).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(counts.balance(warehouse, item)).isEqualTo("8.000000|16.0000");
    }

    private void complete(UUID publication) {
        jdbc.sql("UPDATE event_publication SET completion_date = ?, status = 'COMPLETED' WHERE id = ?")
                .params(Timestamp.from(Instant.now()), publication).update();
    }
}
