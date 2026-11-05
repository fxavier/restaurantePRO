package com.xavier.backend.settlement.controller;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A12 (payment and closing paths, D39, D42): a waiter calling a payment or closing endpoint gets 403; a POS session
 * of store A acting on store B gets 403; a BackOffice session calling a POS write gets 403; an unknown order is 404.
 */
@SettlementModuleTest
class SettlementAccessHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    Sale sale;

    @BeforeEach
    void aSale() throws Exception {
        sale = fixtures.sale("2");
    }

    @Test
    void aWaiterCannotCollectIssueCloseOrResolve() throws Exception {
        RequestPostProcessor waiter = sale.cashier().with(Permission.ORDER_VIEW, Permission.ORDER_EDIT);

        mvc.perform(payment(sale.order()).with(waiter)).andExpect(status().isForbidden());
        mvc.perform(command("/api/v1/pos/orders/" + sale.order() + "/documents", "{}").with(waiter))
                .andExpect(status().isForbidden());
        mvc.perform(command("/api/v1/pos/orders/" + sale.order() + "/close", "{\"orderVersion\": 1}").with(waiter))
                .andExpect(status().isForbidden());
        mvc.perform(command("/api/v1/pos/cash/sessions/" + sale.session() + "/close",
                "{\"version\": 0, \"counts\": []}").with(waiter)).andExpect(status().isForbidden());
    }

    @Test
    void aPosSessionOfAnotherStoreGets403() throws Exception {
        Pos stranger = CashFixtures.pos(UUID.randomUUID());
        RequestPostProcessor elsewhere = stranger.with(Permission.PAYMENT_COLLECT, Permission.ORDER_VIEW);

        mvc.perform(payment(sale.order()).with(elsewhere)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pos/orders/" + sale.order() + "/settlement").with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(command("/api/v1/pos/orders/" + sale.order() + "/close", "{\"orderVersion\": 1}")
                .with(elsewhere)).andExpect(status().isForbidden());
    }

    @Test
    void attemptCommandsNeedTheirPermissionInTheAttemptsStore() throws Exception {
        UUID attempt = SettlementFixtures.id(fixtures.payCash(sale, "10.00", "10.00"));
        RequestPostProcessor elsewhere = CashFixtures.pos(UUID.randomUUID())
                .with(Permission.PAYMENT_COLLECT, Permission.PAYMENT_RESOLVE);

        mvc.perform(command(SettlementFixtures.ATTEMPTS + "/" + attempt + "/resolve", "{}")
                .with(sale.cashier().with(Permission.PAYMENT_COLLECT))).andExpect(status().isForbidden());
        mvc.perform(command(SettlementFixtures.ATTEMPTS + "/" + attempt + "/confirm", "{}").with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(command(SettlementFixtures.ATTEMPTS + "/" + attempt + "/cancel", "{\"reason\": \"x\"}")
                .with(elsewhere)).andExpect(status().isForbidden());
        mvc.perform(command(SettlementFixtures.ATTEMPTS + "/" + attempt + "/resolve", "{}").with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(get(SettlementFixtures.ATTEMPTS + "/" + attempt).with(elsewhere))
                .andExpect(status().isForbidden());
    }

    @Test
    void aWaiterCannotCloseTheDay() throws Exception {
        String day = fixtures.currentDay(sale.cashier()).get("id").asString();

        mvc.perform(command("/api/v1/pos/cash/days/" + day + "/operational-close", "{\"version\": 0}")
                .with(sale.cashier().with(Permission.ORDER_VIEW, Permission.ORDER_EDIT, Permission.CASH_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBackOfficeSessionCannotCallAPosWrite() throws Exception {
        mvc.perform(payment(sale.order()).with(CashFixtures.account(null, Permission.PAYMENT_COLLECT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        mvc.perform(get("/api/v1/pos/orders/" + UUID.randomUUID() + "/settlement")
                .with(sale.cashier().with(Permission.ORDER_VIEW))).andExpect(status().isNotFound());
        mvc.perform(payment(UUID.randomUUID()).with(sale.cashier().with(Permission.PAYMENT_COLLECT)))
                .andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder payment(UUID order) {
        return command(SettlementFixtures.ATTEMPTS, """
                {"orderId": "%s", "methodCode": "CASH", "amount": 25.00, "tendered": 25.00}""".formatted(order));
    }

    private static MockHttpServletRequestBuilder command(String path, String body) {
        return idem(post(path)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
