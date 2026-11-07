package com.xavier.backend.inventory.count.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.organization.MutableClock;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D119 (amended) {@code CountBacklogTest}: approval is refused (409 CONSUMPTION_BACKLOG) while a consumption
 * publication of the count's store, for a round confirmed (or a void made) at or before the latest cut-off, is
 * pending; once it completes, approval goes through. Another store's backlog, or a round after the cut-off, does not
 * block. S47's listeners are not in this context, so a held listener is its registry row: an incomplete
 * {@code event_publication} of a listener in {@code com.xavier.backend.inventory.consumption}, with the event
 * serialized by the registry's own serializer, completed to release it.
 */
@InventoryModuleTest
class CountBacklogTest {

    static final String ROUND_LISTENER = "com.xavier.backend.inventory.consumption.RoundConsumptionListener"
            + ".on(com.xavier.backend.orders.model.event.OrderBatchConfirmed)";
    static final String VOID_LISTENER = "com.xavier.backend.inventory.consumption.VoidConsumptionListener"
            + ".on(com.xavier.backend.orders.model.event.OrderLinesVoided)";

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
    EventSerializer serializer;

    final List<UUID> held = new ArrayList<>();
    CountFixtures counts;
    UUID store;
    UUID warehouse;
    UUID item;
    UUID count;

    @BeforeEach
    void submittedCount() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc, clock);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        counts.receipt(warehouse, item, "10", "2", clock.instant().minusSeconds(3600));
        count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, item, "8", counts.at(60), null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
    }

    @AfterEach
    void release() {
        held.forEach(this::complete);
    }

    @Test
    void approvalWaitsForTheStoresConsumptionBacklogAndGoesThroughOnceItIsProcessed() throws Exception {
        UUID round = pending(ROUND_LISTENER, round(store, counts.at(30)));

        counts.approve(store, count).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSUMPTION_BACKLOG"));
        assertThat(counts.movementsOf(count)).isEmpty();
        assertThat(counts.balance(warehouse, item)).isEqualTo("10.000000|20.0000");

        complete(round);

        counts.approve(store, count).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(counts.balance(warehouse, item)).isEqualTo("8.000000|16.0000");
    }

    @Test
    void aPendingVoidUpToTheCutOffBlocksToo() throws Exception {
        UUID voided = pending(VOID_LISTENER, new OrderLinesVoided(UUID.randomUUID(), UUID.randomUUID(), store,
                UUID.randomUUID(), 1, null, null, BigDecimal.ONE, "wrong dish", counts.at(45)));

        counts.approve(store, count).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSUMPTION_BACKLOG"));
        complete(voided);
        counts.approve(store, count).andExpect(status().isOk());
    }

    @Test
    void anotherStoresBacklogOrARoundAfterTheCutOffDoesNotBlock() throws Exception {
        pending(ROUND_LISTENER, round(UUID.randomUUID(), counts.at(30)));
        pending(ROUND_LISTENER, round(store, counts.at(61)));

        counts.approve(store, count).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private static OrderBatchConfirmed round(UUID storeId, Instant confirmedAt) {
        return new OrderBatchConfirmed(UUID.randomUUID(), UUID.randomUUID(), 1, storeId, UUID.randomUUID(), 1, null,
                UUID.randomUUID(), confirmedAt, List.of());
    }

    private UUID pending(String listenerId, Object event) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                            status, completion_attempts)
                        VALUES (?, ?, ?, ?, ?, 'PROCESSING', 1)""")
                .params(id, listenerId, event.getClass().getName(), serializer.serialize(event).toString(),
                        Timestamp.from(Instant.now()))
                .update();
        held.add(id);
        return id;
    }

    private void complete(UUID publication) {
        jdbc.sql("""
                        UPDATE event_publication SET completion_date = ?, status = 'COMPLETED'
                        WHERE id = ? AND completion_date IS NULL""")
                .params(Timestamp.from(Instant.now()), publication).update();
    }
}
