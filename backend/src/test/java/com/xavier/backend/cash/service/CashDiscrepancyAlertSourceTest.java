package com.xavier.backend.cash.service;

import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D150 CASH_DISCREPANCY: per store, the closings submitted or approved in the last 24 h whose Σ |difference| exceeds
 * {@code restaurantpro.alerts.cash-difference-threshold} (default 0), refreshed every minute into the gauge
 * {@code cash_closings_with_difference{store}}. Each test has its own store whose one session took a 100.00 float and
 * was counted at 95.00: the closing's cash line is 5.00 short.
 */
@CashModuleTest
class CashDiscrepancyAlertSourceTest {

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashDiscrepancyAlertSource source;

    @Autowired
    CashClosingRepository closings;

    @Autowired
    CashTestClock clock;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MockMvc mvc;

    UUID store;
    UUID register;
    UUID day;
    Pos operator;

    @BeforeEach
    void aCountedSessionFiveShort() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "95.00");
        day = fixtures.dayOf(session);
    }

    @AfterEach
    void releaseClock() {
        clock.release();
    }

    @Test
    void aSubmittedClosingWithADifferenceIsAWarningOfItsStoreAndMovesTheGauge() throws Exception {
        fixtures.submittedClosing(operator, register, day);

        source.refresh();

        assertThat(ofStore(source.alerts()))
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::count)
                .containsExactly(tuple("CASH_DISCREPANCY", AlertSeverity.WARNING, 1L));
        assertThat(meters.get("cash_closings_with_difference").tag("store", store.toString()).gauge().value())
                .isEqualTo(1);
    }

    @Test
    void anApprovedClosingStillCountsAndTheAlertClearsAfter24Hours() throws Exception {
        UUID closing = fixtures.submittedClosing(operator, register, day);
        fixtures.approveClosing(store, closing);
        source.refresh();
        assertThat(ofStore(source.alerts())).hasSize(1);

        clock.set(clock.instant().plus(Duration.ofHours(24)).plusSeconds(1));
        source.refresh();

        assertThat(ofStore(source.alerts())).isEmpty();
        assertThat(meters.get("cash_closings_with_difference").tag("store", store.toString()).gauge().value())
                .as("a store once seen reports 0").isZero();
    }

    @Test
    void aRejectedClosingIsNotCounted() throws Exception {
        UUID closing = fixtures.submittedClosing(operator, register, day);
        mvc.perform(idem(post(CashFixtures.BO_CLOSINGS + "/" + closing + "/reject"))
                        .with(CashFixtures.account(store, Permission.CASH_CLOSING_APPROVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "recount the coins"}"""))
                .andExpect(status().isOk());

        source.refresh();

        assertThat(ofStore(source.alerts())).isEmpty();
    }

    @Test
    void aDraftIsNotCounted() throws Exception {
        mvc.perform(idem(post(CashFixtures.POS_CLOSINGS)).with(operator.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerId": "%s", "cashDayId": "%s"}""".formatted(register, day)))
                .andExpect(status().isCreated());

        source.refresh();

        assertThat(ofStore(source.alerts())).isEmpty();
    }

    @Test
    void onlyADifferenceAboveTheThresholdCounts() throws Exception {
        fixtures.submittedClosing(operator, register, day);
        CashDiscrepancyAlertSource atFive = new CashDiscrepancyAlertSource(closings, new SimpleMeterRegistry(), clock,
                new BigDecimal("5.00"));
        CashDiscrepancyAlertSource belowFive = new CashDiscrepancyAlertSource(closings, new SimpleMeterRegistry(),
                clock, new BigDecimal("4.99"));

        atFive.refresh();
        belowFive.refresh();

        assertThat(ofStore(atFive.alerts())).as("5.00 does not exceed 5.00").isEmpty();
        assertThat(ofStore(belowFive.alerts())).hasSize(1);
    }

    private List<OperationalAlert> ofStore(List<OperationalAlert> alerts) {
        return alerts.stream().filter(alert -> store.equals(alert.storeId())).toList();
    }
}
