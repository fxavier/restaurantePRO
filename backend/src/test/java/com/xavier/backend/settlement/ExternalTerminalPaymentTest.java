package com.xavier.backend.settlement;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * An external card or mobile-money terminal (D50 §7.5, P10): the attempt waits PENDING until the operator confirms
 * what the terminal approved (→ Payment and receipt of that method) or cancels it. While it waits, no second
 * attempt starts on the order (409 PAYMENT_IN_DOUBT, §7.4).
 */
@SettlementModuleTest
class ExternalTerminalPaymentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    MockMvc mvc;

    String card;
    Sale sale;

    @BeforeEach
    void aSaleAndACardTerminal() throws Exception {
        card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        sale = fixtures.sale("2"); // 25.00
    }

    @Test
    void anExternalTerminalAttemptWaitsAndItsConfirmationPaysIntoTheSession() throws Exception {
        UUID attempt = SettlementFixtures.id(payByCard("25.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")));
        assertThat(fixtures.payments(sale.order())).isZero();

        confirm(attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.authorizationCode").value("A1B2"));

        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                WHERE p.attempt_id = ? AND m.method_code = ? AND m.type = 'PAYMENT_RECEIPT'""", attempt, card))
                .isEqualTo(1);
    }

    @Test
    void aConfirmationRepeatedWithANewKeyPaysOnce() throws Exception {
        UUID attempt = SettlementFixtures.id(payByCard("25.00"));
        confirm(attempt, UUID.randomUUID().toString()).andExpect(status().isOk());

        confirm(attempt, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_ATTEMPT_STATE"));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }

    @Test
    void aPendingAttemptBlocksASecondAttemptOnTheOrder() throws Exception {
        payByCard("10.00").andExpect(status().isCreated());

        fixtures.payCash(sale, "15.00", "15.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
    }

    @Test
    void cancellingTheOnlyAttemptReleasesThePaymentLock() throws Exception {
        UUID attempt = SettlementFixtures.id(payByCard("25.00"));
        assertThat(billingLock()).isEqualTo("PAYMENT");

        cancel(attempt).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.resolutionReason").value("card declined"));

        assertThat(billingLock()).isEqualTo("NONE");
        assertThat(fixtures.payments(sale.order())).isZero();
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
    }

    @Test
    void cancellingAfterAPaymentKeepsTheLock() throws Exception {
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());
        UUID attempt = SettlementFixtures.id(payByCard("15.00"));

        cancel(attempt).andExpect(status().isOk());

        assertThat(billingLock()).isEqualTo("PAYMENT");
    }

    @Test
    void changeIsForCashOnly() throws Exception {
        fixtures.pay(sale.cashier(), sale.order(), card, "25.00", "30.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHANGE_FOR_CASH_ONLY"));
    }

    private ResultActions payByCard(String amount) throws Exception {
        return fixtures.pay(sale.cashier(), sale.order(), card, amount, null, UUID.randomUUID().toString());
    }

    private ResultActions confirm(UUID attempt, String key) throws Exception {
        return mvc.perform(idem(post(SettlementFixtures.ATTEMPTS + "/" + attempt + "/confirm"), key)
                .with(sale.cashier().with(Permission.PAYMENT_COLLECT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\": \"A1B2\"}"));
    }

    private ResultActions cancel(UUID attempt) throws Exception {
        return mvc.perform(idem(post(SettlementFixtures.ATTEMPTS + "/" + attempt + "/cancel"))
                .with(sale.cashier().with(Permission.PAYMENT_COLLECT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"card declined\"}"));
    }

    private String billingLock() {
        return fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'PAYMENT'",
                sale.order()) == 1 ? "PAYMENT" : "NONE";
    }
}
