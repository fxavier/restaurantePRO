package com.xavier.backend.orders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A table move (spec §4, r2-decisions §4 dining): the order sits at the new table, its code comes from dining's moved
 * occupation, the old occupation closes as MOVED. An occupied target is a conflict.
 */
@OrdersModuleTest
class TableMoveHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID free;

    @BeforeEach
    void anOrderAtM1AndAFreeTableM7() throws Exception {
        shop = fixtures.shop();
        order = fixtures.openedAt(shop, shop.table(), employee, terminal);
        fixtures.confirmedLine(shop, order, "1", 0, employee, terminal);
        free = fixtures.newTable(shop, "M7");
    }

    @Test
    void theOrderMovesWithItsTableCodeAndTheOldTableIsFreed() throws Exception {
        move(1, free).andExpect(status().isOk())
                .andExpect(jsonPath("$.tableId").value(free.toString()))
                .andExpect(jsonPath("$.tableCode").value("M7"))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(fixtures.count("""
                SELECT count(*) FROM orders.order_header WHERE id = ? AND table_id = ? AND table_code = 'M7'""",
                order, free)).isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND table_id = ?
                    AND closed_at IS NULL""", order, free)).isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND table_id = ?
                    AND close_reason = 'MOVED'""", order, shop.table())).isOne();
        assertThat(fixtures.audits("ORDER_TABLE_MOVED", order)).isOne();
    }

    @Test
    void anOccupiedTargetIsAConflictAndNothingMoves() throws Exception {
        UUID occupied = fixtures.newTable(shop, "M8");
        fixtures.openedAt(shop, occupied, employee, terminal);

        move(1, occupied).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));
        move(1, shop.table()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));

        assertThat(fixtures.order(shop, order).get("tableId").asString()).isEqualTo(shop.table().toString());
        assertThat(fixtures.order(shop, order).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void aLockedOrForeignTargetIsRefused() throws Exception {
        fixtures.lockTable(shop, free);
        move(1, free).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TABLE_LOCKED"));

        Shop other = fixtures.shop();
        move(1, other.table()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STORE_MISMATCH"));
    }

    @Test
    void anOrderWithoutATableCannotMove() throws Exception {
        String counter = mvc.perform(post(OrdersFixtures.POS + "/orders")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"COUNTER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        fixtures.moveTable(shop, OrdersFixtures.id(OrdersFixtures.json(counter)), 0, free, employee, terminal,
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ORDER_HAS_NO_TABLE"));
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        move(0, free).andExpect(status().isConflict());
    }

    @Test
    void theSameKeyReplaysTheMove() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = fixtures.moveTable(shop, order, 1, free, employee, terminal, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String replay = fixtures.moveTable(shop, order, 1, free, employee, terminal, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.audits("ORDER_TABLE_MOVED", order)).isOne();
    }

    ResultActions move(long version, UUID table) throws Exception {
        return fixtures.moveTable(shop, order, version, table, employee, terminal, UUID.randomUUID().toString());
    }
}
