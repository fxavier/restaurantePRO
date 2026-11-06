package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * D81 (4): {@code payment_attempts_in_doubt{store}} counts a store's UNKNOWN attempts, and an attempt in doubt for
 * more than 30 minutes is counted by {@code payment_attempts_in_doubt_overdue{store}} (the alert metric) and logged
 * at WARN once. The clock is moved, never slept; the poller runs hourly in module tests, so the attempt stays UNKNOWN.
 */
@SettlementModuleTest
@ExtendWith(OutputCaptureExtension.class)
class InDoubtGaugeTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    MeterRegistry meters;

    @Autowired
    InDoubtGauge gauge;

    Sale sale;
    UUID attempt;

    @BeforeEach
    void anAttemptInDoubt() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG);
        attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
    }

    @AfterEach
    void restore() {
        clock.reset();
        provider.willNext(Behaviour.SUCCEED);
    }

    @Test
    void theInDoubtGaugeIsTaggedWithTheStore() {
        gauge.refresh();

        assertThat(inDoubt("payment_attempts_in_doubt")).isEqualTo(1);
        assertThat(inDoubt("payment_attempts_in_doubt_overdue")).isZero();
    }

    @Test
    void anAttemptInDoubtForMoreThanHalfAnHourIsCountedAsOverdueAndWarnedOnce(CapturedOutput output) {
        clock.advance(Duration.ofMinutes(31));

        gauge.refresh();
        gauge.refresh();

        assertThat(inDoubt("payment_attempts_in_doubt_overdue")).isEqualTo(1);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("in doubt since")
                && line.contains(attempt.toString()))).as("warned once").hasSize(1);
    }

    private double inDoubt(String name) {
        return meters.get(name).tag("store", sale.store().toString()).gauge().value();
    }
}
