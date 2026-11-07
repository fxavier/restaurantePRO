package com.xavier.backend.inventory.consumption.flow;

import static com.xavier.backend.inventory.consumption.flow.LiveStore.roundPublications;
import static com.xavier.backend.inventory.consumption.flow.LiveStore.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementTestClock;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D117, A45, D133 "a split line voided later reverses the wrong consumption" (with a transfer until S46 adds splits):
 * a transfer posts no stock (the target line has no round); voiding the transferred line at its target reverses the
 * source round line's consumption, through {@code VoidFacts.consumedLineId}, once and in proportion; voiding the rest
 * at the source reverses what is left and no more.
 */
@ConsumptionFlowTest
class TransferredLineVoidConsumptionTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures sales;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    InventoryFixtures inventory;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aVoidAtTheTransferTargetReversesTheSourceLinesConsumptionOnce() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
        Sale sale = sales.sale("1");
        waitUntil(() -> roundPublications(jdbc, sale.order(), true) == 1, "the first round's consumption");
        LiveStore live = LiveStore.of(sale, clock.instant(), mvc, inventory);
        var cashier = sale.cashier();
        sales.confirmRound(sale.shop(), cashier, sale.order(), "2", sales.orderVersion(cashier, sale.order()));
        UUID line = jdbc.sql("""
                        SELECT l.id FROM orders.order_line l JOIN orders.order_batch b ON b.id = l.batch_id
                        WHERE l.order_id = ? ORDER BY b.batch_no DESC LIMIT 1""")
                .param(sale.order()).query(UUID.class).single();
        waitUntil(() -> live.movements(jdbc, "SALE_CONSUMPTION") == 1, "the round consumed");
        UUID target = orders.openedAt(sale.shop(), orders.newTable(sale.shop(), "T" + UUID.randomUUID()
                .toString().substring(0, 5)), cashier.employeeId(), cashier.terminalId());

        orders.transfer(sale.shop(), sale.order(), sales.orderVersion(cashier, sale.order()), target,
                        sales.orderVersion(cashier, target), Map.of(line, "1"), false, cashier.employeeId(),
                        cashier.terminalId(), UUID.randomUUID().toString())
                .andExpect(status().is2xxSuccessful());
        UUID moved = jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();
        voidLine(sale, target, moved, "1", false);

        waitUntil(() -> live.movements(jdbc, "CONSUMPTION_REVERSAL") == 1, "the target's void reversed");
        assertThat(live.movements(jdbc, "SALE_CONSUMPTION")).isEqualTo(1);
        assertThat(reversals(line)).singleElement().satisfies(r -> {
            assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo("0.25");
            assertThat((BigDecimal) r.get("unit_cost")).isEqualByComparingTo("2");
        });

        voidLine(sale, sale.order(), line, "1", true);

        waitUntil(() -> live.movements(jdbc, "WASTE") == 1, "the source's void reversed and wasted");
        assertThat(reversals(line)).extracting(r -> (BigDecimal) r.get("quantity"))
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("0.25"), new BigDecimal("0.25"));
        assertThat(live.flourInStock(jdbc)).isEqualByComparingTo("99.75");
    }

    private void voidLine(Sale sale, UUID order, UUID line, String quantity, boolean wasted) throws Exception {
        mvc.perform(post(SettlementFixtures.ORDERS + "/" + order + "/voids")
                        .with(OrdersActors.pos(sale.store(), UUID.randomUUID(), UUID.randomUUID(),
                                Permission.ORDER_VOID)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "lineId": "%s", "quantity": %s, "reason": "error", "wasted": %s}"""
                                .formatted(versionOf(order), line, quantity, wasted)))
                .andExpect(status().isCreated());
    }

    private long versionOf(UUID order) {
        return jdbc.sql("SELECT version FROM orders.order_header WHERE id = ?").param(order).query(Long.class)
                .single();
    }

    private List<Map<String, Object>> reversals(UUID line) {
        return jdbc.sql("""
                        SELECT quantity, unit_cost FROM inventory.stock_movement
                        WHERE source_line_id = ? AND type = 'CONSUMPTION_REVERSAL' ORDER BY posting_seq""")
                .param(line).query().listOfRows();
    }
}
