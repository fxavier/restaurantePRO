package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.InDoubtResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D81 (1), (2), A25 polling half: the {@link InDoubtResolver}, with no request and no actor, asks the provider about
 * each UNKNOWN attempt when it is due (a minute after it became UNKNOWN, then with backoff) and records a final answer
 * through the resolve's own path, audited as the system: exactly one Payment. A provider that does not know the
 * reference ({@code NotFound}) fails the attempt only past the provider's acceptance window (PT15M); inside it the
 * attempt stays UNKNOWN and the next poll is backed off. The resolver is driven by hand with the test clock.
 */
@SettlementModuleTest
class InDoubtPollingTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    InDoubtResolver resolver;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    JdbcClient jdbc;

    Sale sale;
    UUID attempt;

    @BeforeEach
    void anUnknownAttempt() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG); // the provider takes the money, settlement times out
        attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        provider.willNext(Behaviour.SUCCEED);
    }

    @AfterEach
    void restore() {
        clock.reset();
    }

    @Test
    void pollingResolvesAnUnknownAttemptIntoExactlyOnePaymentAsTheSystem() {
        resolver.poll();
        assertThat(status()).as("not due before a minute").isEqualTo("UNKNOWN");

        clock.advance(Duration.ofMinutes(2));
        resolver.poll();
        resolver.poll();

        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
        assertThat(fixtures.receipts(sale.order())).isEqualTo(1);
        assertThat(provider.charges(attempt.toString())).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'PAYMENT_ATTEMPT_RESOLVED' "
                + "AND entity_id = ? AND actor_kind = 'SYSTEM'", attempt.toString())).isEqualTo(1);
    }

    @Test
    void notFoundInsideTheAcceptanceWindowStaysUnknownAndBacksOff() {
        provider.forget(attempt.toString());

        clock.advance(Duration.ofMinutes(2));
        resolver.poll();

        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("SELECT poll_count FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_poll_answer FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(String.class).single()).isEqualTo("NotFound");
        Instant polled = jdbc.sql("SELECT last_polled_at FROM settlement.payment_attempt WHERE id = ?")
                .param(attempt).query(Instant.class).single();
        assertThat(jdbc.sql("SELECT next_poll_at FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(Instant.class).single()).isEqualTo(polled.plus(Duration.ofMinutes(2)));
        assertThat(fixtures.payments(sale.order())).isZero();
    }

    @Test
    void notFoundPastTheAcceptanceWindowFailsTheAttemptAndReleasesTheOrder() {
        provider.forget(attempt.toString());

        clock.advance(Duration.ofMinutes(16));
        resolver.poll();

        assertThat(status()).isEqualTo("FAILED");
        assertThat(fixtures.payments(sale.order())).isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = 'NONE'",
                sale.order())).isEqualTo(1);
    }

    @Test
    void aPendingAnswerIsNotFinalAndIsAskedAgainLater() {
        provider.decide(attempt.toString(), new ProviderOutcome.Pending());

        clock.advance(Duration.ofMinutes(2));
        resolver.poll();
        resolver.poll(); // not due again yet: one query

        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("SELECT poll_count FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(Integer.class).single()).isEqualTo(1);

        provider.decide(attempt.toString(), new ProviderOutcome.Succeeded("FAKE-late"));
        clock.advance(Duration.ofMinutes(3));
        resolver.poll();
        assertThat(status()).isEqualTo("SUCCEEDED");
        assertThat(fixtures.payments(sale.order())).isEqualTo(1);
    }

    private String status() {
        return jdbc.sql("SELECT status FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(String.class).single();
    }
}
