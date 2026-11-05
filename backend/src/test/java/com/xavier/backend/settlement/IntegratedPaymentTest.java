package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.AttemptSweeper;
import com.xavier.backend.settlement.service.PaymentSubmitter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * A8 and D48 with the fake provider: the attempt is persisted CREATED (202 + Location) before the provider is called
 * outside any transaction; success pays once, a decline fails, a timeout is UNKNOWN. UNKNOWN blocks a new attempt
 * and is settled by a provider query (exactly one Payment) or by an approved reclassification. The sweeper resubmits
 * a CREATED attempt nobody submitted and turns a PENDING one past the provider timeout into UNKNOWN.
 */
@SettlementModuleTest
class IntegratedPaymentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    AttemptSweeper sweeper;

    @Autowired
    PaymentSubmitter submitter;

    @Autowired
    SettlementTestClock clock;

    String method;
    Sale sale;

    @BeforeEach
    void aSaleAndAnIntegratedMethod() throws Exception {
        method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        sale = fixtures.sale("2"); // 25.00
    }

    @AfterEach
    void restore() {
        provider.willNext(Behaviour.SUCCEED);
        provider.release();
        clock.reset();
        Mockito.reset(submitter);
    }

    @Test
    void theAttemptIsPersistedBeforeTheProviderAndASuccessPaysOnce() throws Exception {
        ResultActions accepted = pay("25.00").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"));
        UUID attempt = SettlementFixtures.id(accepted);
        accepted.andExpect(header().string("Location", SettlementFixtures.ATTEMPTS + "/" + attempt));

        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }

    @Test
    void aDeclineFailsAndReleasesThePaymentLock() throws Exception {
        provider.willNext(Behaviour.DECLINE);
        UUID attempt = SettlementFixtures.id(pay("25.00"));

        JsonNode failed = fixtures.settled(sale.cashier(), attempt);

        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("outcomeDetail").asString()).isEqualTo("declined by the fake");
        assertThat(fixtures.payments(sale.order())).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'NONE'",
                sale.order())).isEqualTo(1);
    }

    @Test
    void aTimeoutIsUnknownNeverFailedAndBlocksANewAttempt() throws Exception {
        provider.willNext(Behaviour.HANG);
        UUID attempt = SettlementFixtures.id(pay("25.00"));

        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"))
                .andExpect(jsonPath("$.attemptId").value(attempt.toString()));
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.openAttempts[0].status").value("UNKNOWN"));
    }

    @Test
    void aProviderQueryResolvesAnUnknownAttemptIntoExactlyOnePayment() throws Exception {
        UUID attempt = unknownAttempt();

        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_ATTEMPT_RESOLVED'"
                + " AND entity_id = ? AND actor_id = ?", attempt.toString(), sale.cashier().employeeId()))
                .isEqualTo(1);
    }

    @Test
    void aResolveRepeatedWithTheSameKeyReplaysTheResponse() throws Exception {
        UUID attempt = unknownAttempt();
        String key = UUID.randomUUID().toString();

        String first = fixtures.resolve(sale.cashier(), attempt, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String again = fixtures.resolve(sale.cashier(), attempt, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(again)).isEqualTo(SettlementFixtures.json(first));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
    }

    @Test
    void anApprovedReclassificationSettlesAnUnknownAttemptWithItsReasonAndApprover() throws Exception {
        UUID attempt = unknownAttempt();
        UUID approver = UUID.randomUUID();
        given(approvers.verify(eq("GER01"), eq("1234"), eq(Permission.PAYMENT_RESOLVE), eq(sale.store())))
                .willAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .as("D40: the approver is verified before any transaction or lock").isFalse();
                    return approver;
                });

        fixtures.reclassify(sale.cashier(), attempt, "FAILED", "not on the bank statement", "GER01")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.resolutionReason").value("not on the bank statement"));

        assertThat(fixtures.payments(sale.order())).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_ATTEMPT_RECLASSIFIED'"
                + " AND entity_id = ? AND approver_id = ?", attempt.toString(), approver)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'NONE'",
                sale.order())).as("a FAILED reclassification releases the payment lock").isEqualTo(1);
    }

    @Test
    void aReclassificationToSucceededPaysOnceIntoTheAttemptsSessionAndReplays() throws Exception {
        UUID attempt = unknownAttempt();
        UUID approver = UUID.randomUUID();
        given(approvers.verify(eq("GER01"), eq("1234"), eq(Permission.PAYMENT_RESOLVE), eq(sale.store())))
                .willReturn(approver);
        String key = UUID.randomUUID().toString();

        String first = fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "seen on the bank statement",
                        "GER01", key)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        String replay = fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "seen on the bank statement",
                        "GER01", key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(SettlementFixtures.json(replay)).isEqualTo(SettlementFixtures.json(first));
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                WHERE p.attempt_id = ? AND m.session_id = ? AND m.method_code = ?""", attempt, sale.session(), method))
                .isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_ATTEMPT_RECLASSIFIED'"
                + " AND entity_id = ? AND approver_id = ?", attempt.toString(), approver)).isEqualTo(1);
    }

    @Test
    void aReclassificationNeedsAnApproverWhoIsNotTheOperator() throws Exception {
        UUID attempt = unknownAttempt();
        given(approvers.verify(any(), any(), any(), any())).willThrow(new ApprovalDeniedException());
        fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "seen on the terminal", "NOBODY")
                .andExpect(status().isForbidden());
        fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "seen on the terminal", null)
                .andExpect(status().isForbidden());

        Mockito.reset(approvers);
        given(approvers.verify(eq("SELF"), any(), any(), any())).willReturn(sale.cashier().employeeId());
        fixtures.reclassify(sale.cashier(), attempt, "SUCCEEDED", "seen on the terminal", "SELF")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
    }

    @Test
    void aFailedAnswerWhileTheChargeIsInFlightLeavesTheAttemptPending() throws Exception {
        provider.arm();
        provider.willNext(Behaviour.DELAYED);
        UUID attempt = SettlementFixtures.id(pay("25.00"));
        awaitStatus(attempt, "PENDING");

        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'PAYMENT'",
                sale.order())).as("the payment lock stays").isEqualTo(1);
        provider.release();

        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
    }

    @Test
    void aProviderFailureSettlesAnUnknownAttemptOnlyOnceNoChargeCanStillBeInFlight() throws Exception {
        UUID attempt = unknownAttempt();
        provider.decide(attempt.toString(), new ProviderOutcome.Failed("never charged"));

        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        clock.advance(Duration.ofMinutes(5)); // past the provider timeout + margin
        fixtures.resolve(sale.cashier(), attempt, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(fixtures.payments(sale.order())).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'NONE'",
                sale.order())).isEqualTo(1);
    }

    @Test
    void theSweeperTurnsAPendingAttemptPastTheProviderTimeoutIntoUnknown() throws Exception {
        provider.willNext(Behaviour.PENDING);
        UUID attempt = SettlementFixtures.id(pay("25.00"));
        awaitStatus(attempt, "PENDING");

        sweeper.sweep();
        assertThat(statusOf(attempt)).as("not past the timeout yet").isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(5));
        sweeper.sweep();
        assertThat(statusOf(attempt)).isEqualTo("UNKNOWN");
    }

    @Test
    void theSweeperSubmitsACreatedAttemptNobodySubmitted() throws Exception {
        Mockito.doNothing().when(submitter).submit(any()); // the submitter "crashed" before taking it
        UUID attempt = SettlementFixtures.id(pay("25.00"));
        Thread.sleep(200);
        assertThat(statusOf(attempt)).isEqualTo("CREATED");
        Mockito.reset(submitter);

        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep();

        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
    }

    private UUID unknownAttempt() throws Exception {
        provider.willNext(Behaviour.HANG);
        UUID attempt = SettlementFixtures.id(pay("25.00"));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        provider.willNext(Behaviour.SUCCEED);
        return attempt;
    }

    private void awaitStatus(UUID attempt, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!expected.equals(statusOf(attempt)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(statusOf(attempt)).isEqualTo(expected);
    }

    private String statusOf(UUID attempt) throws Exception {
        return SettlementFixtures.json(fixtures.attempt(sale.cashier(), attempt)).get("status").asString();
    }

    private ResultActions pay(String amount) throws Exception {
        return fixtures.pay(sale.cashier(), sale.order(), method, amount, null, UUID.randomUUID().toString());
    }
}
