package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A11 (coordination half) and spec §8.4: a session cannot close while a payment attempt under its responsibility is
 * in doubt; once resolved, it closes with its blind count. The order cannot close either (A8).
 */
@SettlementModuleTest
class SessionCloseBlockedByUnknownTest {

    static final String CASH_25 = "{\"methodCode\": \"CASH\", \"amount\": 25.00}";

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @AfterEach
    void restore() {
        provider.willNext(Behaviour.SUCCEED);
    }

    @Test
    void aSessionWithAnUnknownAttemptDoesNotCloseUntilItIsResolved() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        Sale sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        long version = fixtures.session(sale.cashier(), sale.session()).get("version").asLong();

        fixtures.closeSession(sale.cashier(), sale.session(), version, "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"))
                .andExpect(jsonPath("$.attemptId").value(attempt.toString()));
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"));
        assertThat(fixtures.session(sale.cashier(), sale.session()).get("status").asString()).isEqualTo("OPEN");

        provider.willNext(Behaviour.SUCCEED);
        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        fixtures.closeSession(sale.cashier(), sale.session(), version,
                        "{\"methodCode\": \"%s\", \"amount\": 25.00}".formatted(method))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void aSessionWithNothingInDoubtClosesWithItsBlindCount() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.session(sale.cashier(), sale.session()).get("version").asLong();

        fixtures.closeSession(sale.cashier(), sale.session(), version, CASH_25)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.id").value(sale.session().toString()));

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_session_count WHERE session_id = ?",
                sale.session())).isEqualTo(1);
    }

    @Test
    void aStaleVersionIs409() throws Exception {
        Sale sale = fixtures.sale("1");
        long version = fixtures.session(sale.cashier(), sale.session()).get("version").asLong();

        fixtures.closeSession(sale.cashier(), sale.session(), version + 1, "").andExpect(status().isConflict());
    }

    @Test
    void anotherOperatorsSessionIsNotYoursToClose() throws Exception {
        Sale sale = fixtures.sale("1");
        long version = fixtures.session(sale.cashier(), sale.session()).get("version").asLong();

        fixtures.closeSession(CashFixtures.pos(sale.store()), sale.session(), version, "")
                .andExpect(status().isForbidden());
        fixtures.closeSession(CashFixtures.pos(UUID.randomUUID()), sale.session(), version, "")
                .andExpect(status().isForbidden());
    }
}
