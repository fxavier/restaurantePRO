package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A74 for settlement under its policies (V9060, D166): the four {@code PAYMENT_RECOVERY} jobs run with every store in
 * scope and act on store A's and store B's attempts and charges alike, with no {@code RS001}; the payment submitter's
 * task runs in the scope it captured from the submitter. Each job runs on a fresh thread with no actor and no fixture
 * fallback, so it has exactly the scope it would have in production (its {@code @SystemScoped}); the fixtures run on
 * the JUnit thread. The jobs run hourly in module tests and are driven by hand with the test clock.
 */
@SettlementModuleTest
class SettlementActorlessScopeTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    MeterRegistry meters;

    @Autowired
    InDoubtGauge gauge;

    @Autowired
    InDoubtResolver resolver;

    @Autowired
    AttemptSweeper sweeper;

    @Autowired
    CreditChargeSweeper creditSweeper;

    @Autowired
    PaymentSubmitter submitter;

    @Autowired
    CreditSaleSteps steps;

    @Autowired
    StoreScopes scopes;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void restore() {
        Mockito.reset(submitter, steps);
        provider.willNext(Behaviour.SUCCEED);
        clock.reset();
    }

    @Test
    void theInDoubtGaugeCountsTheAttemptsOfEveryStore() throws Exception {
        Sale a = fixtures.sale("2");
        Sale b = fixtures.sale("2");
        attempt(a, Behaviour.HANG, "UNKNOWN");
        attempt(b, Behaviour.HANG, "UNKNOWN");

        onAJobThread(gauge::refresh);

        assertThat(inDoubt(a)).isEqualTo(1);
        assertThat(inDoubt(b)).isEqualTo(1);
    }

    @Test
    void theResolverPollsTheAttemptsInDoubtOfEveryStore() throws Exception {
        Sale a = fixtures.sale("2");
        Sale b = fixtures.sale("2");
        UUID ofA = attempt(a, Behaviour.HANG, "UNKNOWN");
        UUID ofB = attempt(b, Behaviour.HANG, "UNKNOWN");
        clock.advance(Duration.ofMinutes(2));

        onAJobThread(resolver::poll);

        assertThat(status(ofA)).isEqualTo("SUCCEEDED");
        assertThat(status(ofB)).isEqualTo("SUCCEEDED");
    }

    @Test
    void theSweeperTurnsThePendingAttemptsOfEveryStorePastTheTimeoutIntoUnknown() throws Exception {
        Sale a = fixtures.sale("2");
        Sale b = fixtures.sale("2");
        UUID ofA = attempt(a, Behaviour.PENDING, "PENDING");
        UUID ofB = attempt(b, Behaviour.PENDING, "PENDING");
        clock.advance(Duration.ofMinutes(5));

        onAJobThread(sweeper::sweep);

        assertThat(status(ofA)).isEqualTo("UNKNOWN");
        assertThat(status(ofB)).isEqualTo("UNKNOWN");
    }

    @Test
    void theCreditSweeperPostsTheIssuedChargesOfEveryStore() throws Exception {
        Sale a = fixtures.sale("2");
        Sale b = fixtures.sale("2");
        crashedAfterTheIssue(a);
        crashedAfterTheIssue(b);
        clock.advance(Duration.ofMinutes(2)); // past credit-sweep-grace

        onAJobThread(creditSweeper::sweep);

        assertThat(chargeStatus(a)).isEqualTo("POSTED");
        assertThat(chargeStatus(b)).isEqualTo("POSTED");
    }

    @Test
    void aSubmissionRunsInTheScopeTheSubmitterCaptured() throws Exception {
        Sale a = fixtures.sale("2");
        Mockito.doNothing().when(submitter).submit(any()); // the POS request's own submission is lost
        UUID attempt = attempt(a, Behaviour.SUCCEED, "CREATED");
        Mockito.reset(submitter);

        scopes.callForStore(a.store(), "listener:SettlementActorlessScopeTest", () -> {
            submitter.submit(attempt);
            return null;
        });

        assertThat(fixtures.settled(a.cashier(), attempt).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    /** {@code job} on a new thread with no actor and no fixture fallback, as a scheduler would run it. */
    private static void onAJobThread(Runnable job) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().name("s67-job").unstarted(() -> {
            try {
                job.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        thread.start();
        thread.join(Duration.ofSeconds(60));
        assertThat(thread.isAlive()).as("the job finished").isFalse();
        assertThat(failure.get()).as("the job failed").isNull();
    }

    /** An attempt of {@code sale} by an integrated method whose provider behaves as {@code behaviour}. */
    private UUID attempt(Sale sale, Behaviour behaviour, String expected) throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        provider.willNext(behaviour);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        awaitStatus(attempt, expected);
        provider.willNext(Behaviour.SUCCEED);
        return attempt;
    }

    private void crashedAfterTheIssue(Sale sale) throws Exception {
        UUID customer = customers.creditCustomer("100.00");
        Mockito.doThrow(new IllegalStateException("crash before s3")).when(steps).post(any(), any());
        fixtures.creditCharge(sale, customer, "25.00");
        Mockito.reset(steps);
        assertThat(chargeStatus(sale)).isEqualTo("RESERVED");
    }

    private void awaitStatus(UUID attempt, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!expected.equals(status(attempt)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(status(attempt)).isEqualTo(expected);
    }

    private String status(UUID attempt) {
        return jdbc.sql("SELECT status FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(String.class).single();
    }

    private String chargeStatus(Sale sale) {
        return jdbc.sql("SELECT status FROM settlement.credit_charge WHERE order_id = ?").param(sale.order())
                .query(String.class).single();
    }

    private double inDoubt(Sale sale) {
        return meters.get("payment_attempts_in_doubt").tag("store", sale.store().toString()).gauge().value();
    }
}
