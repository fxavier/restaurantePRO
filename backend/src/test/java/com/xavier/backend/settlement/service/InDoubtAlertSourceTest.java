package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.FakePaymentProvider.Behaviour;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D150 PAYMENTS_IN_DOUBT: one CRITICAL alert per store with attempts in doubt for more than 30 minutes, taken from the
 * in-doubt gauge's last refresh (no query of its own). The clock is moved, never slept.
 */
@SettlementModuleTest
class InDoubtAlertSourceTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    FakePaymentProvider provider;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    InDoubtGauge gauge;

    @Autowired
    InDoubtAlertSource source;

    @Autowired
    JdbcClient jdbc;

    Sale sale;
    Instant createdAt;

    @BeforeEach
    void anAttemptInDoubt() throws Exception {
        String method = SettlementFixtures.freshCode("PAY");
        fixtures.method(method, "INTEGRATED", FakePaymentProvider.CODE);
        sale = fixtures.sale("2");
        provider.willNext(Behaviour.HANG);
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, "25.00", null,
                UUID.randomUUID().toString()));
        assertThat(fixtures.settled(sale.cashier(), attempt).get("status").asString()).isEqualTo("UNKNOWN");
        createdAt = jdbc.sql("SELECT created_at FROM settlement.payment_attempt WHERE id = ?").param(attempt)
                .query(java.sql.Timestamp.class).single().toInstant();
    }

    @AfterEach
    void restore() {
        clock.reset();
        provider.willNext(Behaviour.SUCCEED);
    }

    @Test
    void anAttemptInDoubtForHalfAnHourOrLessIsNoAlert() {
        clock.advance(Duration.ofMinutes(29));
        gauge.refresh();

        assertThat(ofStore(source.alerts())).isEmpty();
    }

    @Test
    void anOverdueAttemptIsACriticalAlertOfItsStoreFromTheLastRefresh() {
        gauge.refresh();
        clock.advance(Duration.ofMinutes(31));
        assertThat(ofStore(source.alerts())).as("not overdue at the last refresh").isEmpty();

        gauge.refresh();

        assertThat(ofStore(source.alerts()))
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::count,
                        OperationalAlert::since)
                .containsExactly(tuple("PAYMENTS_IN_DOUBT", AlertSeverity.CRITICAL, 1L, createdAt));
    }

    private List<OperationalAlert> ofStore(List<OperationalAlert> alerts) {
        return alerts.stream().filter(alert -> sale.store().equals(alert.storeId())).toList();
    }
}
