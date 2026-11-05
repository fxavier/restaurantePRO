package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Transfers between orders (spec §4 "transferência parcial/total", §8.2: the right to charge moves, production and stock
 * never see it twice; r2-decisions §4 orders): same store, both OPEN, the source with no payment under way, the target
 * not frozen nor at a locked table.
 */
@OrdersModuleTest
class TransferHttpTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersApi ordersApi;

    final UUID employee = UUID.randomUUID();
    final UUID mover = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID source;
    UUID target;
    UUID targetTable;
    UUID three;
    UUID one;

    @BeforeEach
    void twoOrdersAtTwoTables() throws Exception {
        shop = fixtures.shop();
        source = fixtures.openedAt(shop, shop.table(), employee, terminal);
        three = fixtures.confirmedLine(shop, source, "3", 0, employee, terminal);
        one = fixtures.confirmedLine(shop, source, "1", 1, employee, terminal);
        targetTable = fixtures.newTable(shop, "M2");
        target = fixtures.openedAt(shop, targetTable, employee, terminal);
    }

    @Test
    void aPartialTransferMovesTheRightToChargeWithoutANewRoundAndKeepsTheNetTotal() throws Exception {
        BigDecimal before = fixtures.netQuantity(source, target);

        JsonNode transfer = json(transfer(2, 0, Map.of(three, "2"), false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.version").value(3))
                .andExpect(jsonPath("$.target.version").value(1))
                .andExpect(jsonPath("$.source.due.amount").value(25.00))
                .andExpect(jsonPath("$.target.due.amount").value(25.00))
                .andReturn().getResponse().getContentAsString());

        assertThat(fixtures.netQuantity(source, target)).isEqualByComparingTo(before);
        assertThat(fixtures.netQuantity(target)).isEqualByComparingTo("2");
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_batch WHERE order_id = ?", target)).isZero();
        UUID transferId = UUID.fromString(transfer.get("id").asString());
        assertThat(fixtures.count("""
                SELECT count(*) FROM orders.order_line WHERE order_id = ? AND status = 'CONFIRMED'
                    AND batch_id IS NULL AND origin_transfer_id = ? AND confirmed_by_employee_id = ?""",
                target, transferId, mover)).isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM orders.order_transfer_line WHERE transfer_id = ? AND source_order_id = ?
                    AND target_order_id = ? AND source_line_id = ?""", transferId, source, target, three)).isOne();
        assertThat(fixtures.audits("ORDER_LINES_TRANSFERRED", transferId)).isOne();
    }

    @Test
    void aMergeMovesEverythingAndCancelsTheEmptySourceFreeingItsTable() throws Exception {
        Map<UUID, String> all = new LinkedHashMap<>();
        all.put(three, "3");
        all.put(one, "1");

        transfer(2, 0, all, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.lifecycle").value("CANCELLED"))
                .andExpect(jsonPath("$.target.due.amount").value(50.00));

        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND closed_at IS NULL""", source))
                .isZero();
        assertThat(fixtures.netQuantity(target)).isEqualByComparingTo("4");
    }

    @Test
    void aSourceThatStillBillsSomethingIsNotCancelled() throws Exception {
        transfer(2, 0, Map.of(three, "3"), true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.lifecycle").value("OPEN"));
    }

    @Test
    void aLineNamedTwiceIsCheckedForItsTotalAndMovesAsOneCopy() throws Exception {
        rawTransfer("""
                [{"lineId": "%s", "quantity": 2}, {"lineId": "%s", "quantity": 2}]""".formatted(three, three))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_NET"));
        assertThat(fixtures.netQuantity(target)).isZero();
        assertThat(fixtures.netQuantity(source)).isEqualByComparingTo("4");

        rawTransfer("""
                [{"lineId": "%s", "quantity": 1}, {"lineId": "%s", "quantity": 1}]""".formatted(three, three))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line WHERE order_id = ? AND quantity = 2",
                target)).isOne();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line WHERE order_id = ?", target)).isOne();
        assertThat(fixtures.netQuantity(source, target)).isEqualByComparingTo("4");
    }

    @Test
    void moreThanTheNetQuantityIsRefusedAndNothingMoves() throws Exception {
        transfer(2, 0, Map.of(three, "3.5"), false).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_NET"));

        assertThat(fixtures.netQuantity(target)).isZero();
        assertThat(fixtures.order(shop, source).get("version").asLong()).isEqualTo(2);
        assertThat(fixtures.order(shop, target).get("version").asLong()).isZero();
    }

    @Test
    void aSourceWithAPaymentUnderWayCannotGiveLinesAway() throws Exception {
        fixtures.inPos(shop, () -> ordersApi.lockForPayment(source));

        transfer(3, 0, Map.of(three, "1"), false).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IN_SETTLEMENT"));
    }

    @Test
    void aTargetWithAPaymentUnderWayStillTakesLinesButAFrozenOneDoesNot() throws Exception {
        fixtures.inPos(shop, () -> ordersApi.lockForPayment(target));
        transfer(2, 1, Map.of(three, "1"), false).andExpect(status().isCreated());

        fixtures.inPos(shop, () -> ordersApi.freezeForInvoice(target));
        transfer(3, 3, Map.of(three, "1"), false).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_FROZEN"));
    }

    @Test
    void aTargetAtALockedTableTakesNoLines() throws Exception {
        fixtures.lockTable(shop, targetTable);

        transfer(2, 0, Map.of(three, "1"), false).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TABLE_LOCKED"));
    }

    @Test
    void staleVersionsOfEitherOrderAreAConflict() throws Exception {
        transfer(1, 0, Map.of(three, "1"), false).andExpect(status().isConflict());
        transfer(2, 5, Map.of(three, "1"), false).andExpect(status().isConflict());

        assertThat(fixtures.netQuantity(target)).isZero();
    }

    @Test
    void anOrderCannotTransferToItself() throws Exception {
        fixtures.transfer(shop, source, 2, source, 2, Map.of(three, "1"), false, mover, terminal,
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SAME_ORDER"));
    }

    @Test
    void aTargetOfAnotherStoreIsForbidden() throws Exception {
        Shop other = fixtures.shop();
        UUID foreign = fixtures.openedAt(other, other.table(), employee, terminal);

        fixtures.transfer(shop, source, 2, foreign, 0, Map.of(three, "1"), false, mover, terminal,
                UUID.randomUUID().toString()).andExpect(status().isForbidden());
    }

    @Test
    void theSameKeyReplaysTheTransferOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = fixtures.transfer(shop, source, 2, target, 0, Map.of(three, "1"), false, mover, terminal, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String replay = fixtures.transfer(shop, source, 2, target, 0, Map.of(three, "1"), false, mover, terminal, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.netQuantity(target)).isEqualByComparingTo("1");
    }

    /** A transfer whose {@code lines} JSON array is sent as written (a map cannot name a line twice). */
    ResultActions rawTransfer(String lines) throws Exception {
        return mvc.perform(post(OrdersFixtures.POS + "/orders/" + source + "/transfers")
                .with(OrdersActors.pos(shop.store(), mover, terminal, Permission.ORDER_TRANSFER)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderVersion": 2, "targetOrderId": "%s", "targetOrderVersion": 0, "lines": %s,
                         "cancelSourceWhenEmpty": false}""".formatted(target, lines)));
    }

    ResultActions transfer(long sourceVersion, long targetVersion, Map<UUID, String> lines, boolean cancelWhenEmpty)
            throws Exception {
        return fixtures.transfer(shop, source, sourceVersion, target, targetVersion, lines, cancelWhenEmpty, mover,
                terminal, UUID.randomUUID().toString());
    }
}
