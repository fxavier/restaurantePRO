package com.xavier.backend.inventory.count.service;

import static com.xavier.backend.inventory.InventoryFixtures.ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.count.CountFixtures;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D119/D113 {@code CountApprovalOnceTest}: an approval replayed with its Idempotency-Key answers as recorded and
 * posts once; approving again with a new key is 409 COUNT_STATE; and a ledger replay of the count's source with other
 * values is 409 MOVEMENT_SOURCE_MISMATCH.
 */
@InventoryModuleTest
class CountApprovalOnceTest {

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
    void submittedCountOneShort() throws Exception {
        counts = new CountFixtures(mvc, ledger, tx, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        counts.receipt(warehouse, item, "10", "2", now.minusSeconds(3600));
        count = counts.startedCount(store, warehouse, "REGULAR");
        counts.line(store, count, item, "9", now, null).andExpect(status().isOk());
        counts.command(store, count, "submit", Permission.INVENTORY_COUNT).andExpect(status().isOk());
    }

    @Test
    void aReplayedApprovalPostsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = counts.command(store, count, "approve", Permission.INVENTORY_COUNT_APPROVE, key)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByAccountId").value(ACCOUNT_ID.toString()))
                .andReturn().getResponse().getContentAsString();

        String replay = counts.command(store, count, "approve", Permission.INVENTORY_COUNT_APPROVE, key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(counts.movementsOf(count)).hasSize(1);
        assertThat(counts.balance(warehouse, item)).isEqualTo("9.000000|18.0000");
    }

    @Test
    void approvingAnApprovedCountAgainIsAConflictAndPostsNothing() throws Exception {
        counts.approve(store, count).andExpect(status().isOk());

        counts.approve(store, count).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUNT_STATE"));
        counts.command(store, count, "reject", Permission.INVENTORY_COUNT_APPROVE).andExpect(status().isBadRequest());

        assertThat(counts.movementsOf(count)).hasSize(1);
        assertThat(counts.balance(warehouse, item)).isEqualTo("9.000000|18.0000");
    }

    @Test
    void theLedgerKeyOfACountLineRefusesOtherValues() throws Exception {
        counts.approve(store, count).andExpect(status().isOk());
        UUID line = jdbc.sql("SELECT id FROM inventory.stock_count_line WHERE count_id = ?").param(count)
                .query(UUID.class).single();
        Instant at = jdbc.sql("SELECT counted_at FROM inventory.stock_count_line WHERE id = ?").param(line)
                .query(java.sql.Timestamp.class).single().toInstant();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.post(List.of(
                        PostMovement.of(warehouse, item, MovementType.COUNT_ADJUSTMENT, new BigDecimal("-3"),
                                "STOCK_COUNT", count, LocalDate.ofInstant(at, ZoneOffset.UTC), at)
                                .withSourceLine(line)))))
                .hasMessageContaining("already has stock movements with other values");
        assertThat(counts.balance(warehouse, item)).isEqualTo("9.000000|18.0000");
    }
}
