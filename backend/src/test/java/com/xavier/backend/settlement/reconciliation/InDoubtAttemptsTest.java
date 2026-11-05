package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * PRD S31, D81: the store's attempts still PENDING or UNKNOWN, oldest first, with their age, the provider status
 * queries made and the last answer, for a person to reconcile.
 */
@SettlementModuleTest
class InDoubtAttemptsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SettlementTestClock clock;

    @AfterEach
    void reset() {
        provider.willNext(FakePaymentProvider.Behaviour.SUCCEED);
        clock.reset();
    }

    @Test
    void listsThePendingAndUnknownAttemptsWithTheirAgeAndPolls() throws Exception {
        Sale sale = fixtures.sale("4");
        String card = cardMethod(fixtures);
        UUID pending = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), card, "10.00", null,
                UUID.randomUUID().toString()).andExpect(status().isCreated()));
        Sale other = fixtures.sale("2"); // another store
        String integrated = SettlementFixtures.freshCode("MPESA");
        fixtures.method(integrated, "INTEGRATED", FakePaymentProvider.CODE);
        provider.willNext(FakePaymentProvider.Behaviour.HANG);
        UUID unknown = SettlementFixtures.id(fixtures.pay(other.cashier(), other.order(), integrated, "25.00", null,
                UUID.randomUUID().toString()).andExpect(status().isAccepted()));
        assertThat(fixtures.settled(other.cashier(), unknown).get("status").asString()).isEqualTo("UNKNOWN");
        clock.advance(Duration.ofMinutes(5));

        JsonNode list = SettlementFixtures.json(mvc.perform(get(BASE + "/in-doubt").param("storeId",
                        sale.store().toString()).with(CashFixtures.account(sale.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk()));

        assertThat(list).hasSize(1); // the other sale is another store
        JsonNode first = list.get(0);
        assertThat(first.get("attemptId").asString()).isEqualTo(pending.toString());
        assertThat(first.get("status").asString()).isEqualTo("PENDING");
        assertThat(first.get("ageSeconds").asLong()).isGreaterThanOrEqualTo(300);
        JsonNode inOther = SettlementFixtures.json(mvc.perform(get(BASE + "/in-doubt").param("storeId",
                        other.store().toString()).with(CashFixtures.account(other.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk())).get(0);
        assertThat(inOther.get("attemptId").asString()).isEqualTo(unknown.toString());
        assertThat(inOther.get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(inOther.get("pollCount").asInt()).isZero();
        assertThat(inOther.get("nextPollAt").isNull()).isFalse();
        assertThat(inOther.get("amount").get("amount").decimalValue()).isEqualByComparingTo("25.00");
    }

    @Test
    void needsReconciliationViewInTheStore() throws Exception {
        UUID store = UUID.randomUUID();
        mvc.perform(get(BASE + "/in-doubt").param("storeId", store.toString())
                        .with(CashFixtures.account(store, Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isForbidden());
    }
}
