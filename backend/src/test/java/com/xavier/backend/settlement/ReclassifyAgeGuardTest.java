package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D81 (3), A25: a manual reclassification of an UNKNOWN attempt to FAILED needs the attempt to be older than
 * {@code reclassify-failed-min-age} (PT15M) and at least one provider status query since it became UNKNOWN;
 * otherwise 422 {@code RECLASSIFY_TOO_EARLY} and nothing changes. To SUCCEEDED there is no age guard.
 */
@SettlementModuleTest
class ReclassifyAgeGuardTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    SettlementTestClock clock;

    Sale sale;
    UUID attempt;

    @BeforeEach
    void anUnknownAttemptTheProviderCannotDecide() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG);
        attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        provider.willNext(Behaviour.SUCCEED);
        provider.decide(attempt.toString(), new ProviderOutcome.Pending());
        given(approvers.verify(eq("GER01"), any(), eq(Permission.PAYMENT_RESOLVE), any()))
                .willReturn(UUID.randomUUID());
    }

    @AfterEach
    void restore() {
        clock.reset();
    }

    @Test
    void aYoungAttemptIsNotReclassifiedAsFailed() throws Exception {
        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk());

        fixtures.reclassify(sale.cashier(), attempt, "FAILED", "not on the statement", "GER01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECLASSIFY_TOO_EARLY"));
        assertThat(statusOf()).isEqualTo("UNKNOWN");
    }

    @Test
    void anOldAttemptTheProviderWasNeverAskedAboutIsNotReclassifiedAsFailed() throws Exception {
        clock.advance(Duration.ofMinutes(16));

        fixtures.reclassify(sale.cashier(), attempt, "FAILED", "not on the statement", "GER01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECLASSIFY_TOO_EARLY"))
                .andExpect(jsonPath("$.pollCount").value(0));
        assertThat(statusOf()).isEqualTo("UNKNOWN");
    }

    @Test
    void anOldAttemptAskedAboutIsReclassifiedAsFailed() throws Exception {
        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        clock.advance(Duration.ofMinutes(16));

        fixtures.reclassify(sale.cashier(), attempt, "FAILED", "not on the statement", "GER01")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(fixtures.payments(sale.order())).isZero();
    }

    @Test
    void aReclassificationAsSucceededHasNoAgeGuard() throws Exception {
        fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "on the statement", "GER01")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
    }

    private String statusOf() throws Exception {
        return SettlementFixtures.json(fixtures.attempt(sale.cashier(), attempt)).get("status").asString();
    }
}
