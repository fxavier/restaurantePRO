package com.xavier.backend.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The settlement reads: the POS map's consumption and balance per open order, and the BackOffice pendings (attempts in
 * doubt, fiscal drafts, settled orders still open), plus the {@code payment_attempts_in_doubt} gauge.
 */
@SettlementModuleTest
class SettlementReadsHttpTest {

    static final String OPEN_ORDERS = "/api/v1/pos/settlement/open-orders";
    static final String PENDING = "/api/v1/backoffice/settlement/pending";

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void restore() {
        provider.willNext(Behaviour.SUCCEED);
        Mockito.reset(fiscalAdapter);
    }

    @Test
    void theOpenOrdersOfTheStoreShowWhatIsDuePaidAndLeft() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());

        mvc.perform(get(OPEN_ORDERS).with(sale.cashier().with(Permission.ORDER_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(sale.order().toString()))
                .andExpect(jsonPath("$[0].due.amount").value(25.00))
                .andExpect(jsonPath("$[0].paid.amount").value(10.00))
                .andExpect(jsonPath("$[0].balance.amount").value(15.00))
                .andExpect(jsonPath("$[0].financialState").value("PARTIALLY_SETTLED"))
                .andExpect(jsonPath("$[0].tableId").value(sale.shop().table().toString()))
                .andExpect(jsonPath("$[0].tableCode").value("M1"));
    }

    @Test
    void aFiscalFailureAfterPaymentIsAVisiblePendency() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent());

        mvc.perform(get(PENDING).param("storeId", sale.store().toString())
                        .with(CashFixtures.account(sale.store(), Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inDoubtAttempts").isEmpty())
                .andExpect(jsonPath("$.fiscalDrafts[0].orderId").value(sale.order().toString()))
                .andExpect(jsonPath("$.fiscalDrafts[0].lastError").isNotEmpty())
                .andExpect(jsonPath("$.settledOpenOrders[0].orderId").value(sale.order().toString()));
    }

    @Test
    void anAttemptInDoubtIsPendingAndCounted() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        Sale sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");

        mvc.perform(get(PENDING).param("storeId", sale.store().toString())
                        .with(CashFixtures.account(sale.store(), Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inDoubtAttempts[0].id").value(attempt.toString()))
                .andExpect(jsonPath("$.inDoubtAttempts[0].status").value("UNKNOWN"));
        assertThat(meters.get("payment_attempts_in_doubt").gauge().value()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void thePendingsOfAStoreNeedPaymentResolveThere() throws Exception {
        Sale sale = fixtures.sale("1");

        mvc.perform(get(PENDING).param("storeId", sale.store().toString())
                        .with(CashFixtures.account(UUID.randomUUID(), Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isForbidden());
    }
}
