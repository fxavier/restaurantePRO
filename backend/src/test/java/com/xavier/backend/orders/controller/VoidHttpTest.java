package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.PIN;
import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Voids (spec §4 "anulação após envio exige motivo, autorização e comunicação à produção"; D40, D49; A6): a reason, an
 * approver verified before the transaction, never more than the net quantity, never while a payment is under way.
 */
@OrdersModuleTest
class VoidHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrderEventProbe events;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersProductionApi productionApi;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;
    Employee manager;

    @BeforeEach
    void aDispatchedRoundOfThree() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        UUID batch = jdbc.sql("SELECT batch_id FROM orders.order_line WHERE id = ?").param(line)
                .query(UUID.class).single();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> productionApi.markBatchDispatched(batch));
        manager = fixtures.employee(shop, IdentityFixtures.MANAGER);
    }

    @Test
    void anApprovedVoidLowersTheNetQuantityAndDueIsAuditedWithBothEmployeesAndTellsProduction() throws Exception {
        voidRequest(1, "2", manager.code(), PIN, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineId").value(line.toString()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.approverEmployeeId").value(manager.id().toString()))
                .andExpect(jsonPath("$.order.version").value(2))
                .andExpect(jsonPath("$.order.lines[0].netQuantity").value(1))
                .andExpect(jsonPath("$.order.due.amount").value(12.50));

        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event WHERE action = 'ORDER_LINE_VOIDED' AND actor_id = ?
                    AND approver_id = ? AND reason = 'wrong dish'""", operator, manager.id())).isOne();
        List<OrderLinesVoided> voided = events.voidsOf(order, 1);
        assertThat(voided).singleElement().satisfies(event -> {
            assertThat(event.line().lineId()).isEqualTo(line);
            assertThat(event.line().destination()).isEqualTo(
                    jdbc.sql("SELECT destination FROM orders.order_line WHERE id = ?").param(line)
                            .query(String.class).optional().orElse(null));
            assertThat(event.quantity()).isEqualByComparingTo("2");
            assertThat(event.reason()).isEqualTo("wrong dish");
            assertThat(event.storeId()).isEqualTo(shop.store());
            assertThat(event.terminalId()).isEqualTo(terminal);
            assertThat(event.tableCode()).isEqualTo("M1");
        });
    }

    @Test
    void aVoidWithoutAnApproverIsForbiddenAndWritesNothing() throws Exception {
        voidRequest(1, "1", null, null, UUID.randomUUID().toString()).andExpect(status().isForbidden());

        assertNothingVoided();
    }

    @Test
    void aWrongPinOrAnApproverWithoutTheApprovalPermissionIsForbiddenAndWritesNothing() throws Exception {
        Employee waiter = fixtures.employee(shop, IdentityFixtures.WAITER);

        voidRequest(1, "1", manager.code(), "0000", UUID.randomUUID().toString()).andExpect(status().isForbidden());
        voidRequest(1, "1", waiter.code(), PIN, UUID.randomUUID().toString()).andExpect(status().isForbidden());

        assertNothingVoided();
    }

    @Test
    void theOperatorCannotApproveTheirOwnVoid() throws Exception {
        mvc.perform(voidOf(1, "1", manager.code(), PIN).with(OrdersActors.pos(shop.store(), manager.id(), terminal,
                        Permission.ORDER_VOID)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SELF_APPROVAL"));

        assertNothingVoided();
    }

    @Test
    void moreThanTheNetQuantityIsRefused() throws Exception {
        voidRequest(1, "2", manager.code(), PIN, UUID.randomUUID().toString()).andExpect(status().isCreated());

        voidRequest(2, "1.5", manager.code(), PIN, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_NET"));
    }

    @Test
    void aVoidWhileAPaymentIsUnderWayIsRefused() throws Exception {
        OrdersActors.runAsPos(shop.store(), operator, terminal, () -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    ordersApi.lockForPayment(order);
                    return null;
                }));

        voidRequest(2, "1", manager.code(), PIN, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IN_SETTLEMENT"));
        assertThat(voids()).isZero();
    }

    @Test
    void aStaleOrderVersionIsAConflict() throws Exception {
        voidRequest(0, "1", manager.code(), PIN, UUID.randomUUID().toString()).andExpect(status().isConflict());

        assertNothingVoided();
    }

    @Test
    void aVoidNeedsAReason() throws Exception {
        mvc.perform(post(POS + "/orders/" + order + "/voids").with(pos()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", manager.code()).header("X-Approver-Pin", PIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": 1, "lineId": "%s", "quantity": 1, "reason": " ", "wasted": false}"""
                                .formatted(line)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSameKeyReplaysTheVoidOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = voidRequest(1, "1", manager.code(), PIN, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = voidRequest(1, "1", manager.code(), PIN, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(voids()).isOne();
    }

    @Test
    void aSessionOfAnotherStoreIsForbidden() throws Exception {
        mvc.perform(voidOf(1, "1", manager.code(), PIN).header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(OrdersActors.pos(UUID.randomUUID(), operator, terminal, Permission.ORDER_VOID)))
                .andExpect(status().isForbidden());

        assertNothingVoided();
    }

    void assertNothingVoided() throws Exception {
        assertThat(voids()).isZero();
        assertThat(fixtures.order(shop, order).get("version").asLong()).isEqualTo(1);
    }

    long voids() {
        return fixtures.count("SELECT count(*) FROM orders.order_line_void WHERE order_id = ?", order);
    }

    ResultActions voidRequest(long orderVersion, String quantity, String approverCode, String pin, String key)
            throws Exception {
        return mvc.perform(voidOf(orderVersion, quantity, approverCode, pin).with(pos())
                .header("Idempotency-Key", key));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder voidOf(
            long orderVersion, String quantity, String approverCode, String pin) {
        var request = post(POS + "/orders/" + order + "/voids").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderVersion": %d, "lineId": "%s", "quantity": %s, "reason": "wrong dish", "wasted": true}"""
                        .formatted(orderVersion, line, quantity));
        if (approverCode != null) {
            request.header("X-Approver-Code", approverCode).header("X-Approver-Pin", pin);
        }
        return request;
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor pos() {
        return OrdersActors.pos(shop.store(), operator, terminal, Permission.ORDER_VOID);
    }
}
