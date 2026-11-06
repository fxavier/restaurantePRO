package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.importCsv;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BUG-0002 (docs/qa/bugs/0002-statement-period-ignores-business-day-cutoff.md): a statement's period is business days
 * ({@code StatementImportService.Target}: "the statement's store, method and business days, and those days as
 * instants"; P37/D85: money belongs to its cash day), but its instants are computed from calendar midnights in the
 * store's zone, ignoring the store's business-day cutoff. A card payment confirmed at 00:30 of the next calendar date,
 * before a 05:00 cutoff, belongs to business day D; the statement for D..D refuses its own line (422
 * {@code STATEMENT_INVALID}, "The line is not of the statement's period") and would list the payment of D under D+1.
 *
 * <p>The clock is moved, never slept.
 */
@SettlementModuleTest
class StatementPeriodIsBusinessDaysTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void restore() {
        clock.reset();
    }

    @Test
    void aPaymentAfterMidnightBeforeTheCutoffIsMatchedByItsBusinessDaysStatement() throws Exception {
        ZonedDateTime halfPastMidnight = ZonedDateTime.now(ReconciliationRequests.STORE_ZONE).plusDays(1)
                .with(LocalTime.of(0, 30));
        clock.moveTo(halfPastMidnight.toInstant());
        Sale sale = fixtures.sale("4", LocalTime.of(5, 0));
        String card = cardMethod(fixtures);
        UUID payment = cardPayment(fixtures, sale, card, "25.00", "A-BD1");
        LocalDate businessDay = jdbc.sql("SELECT business_date FROM settlement.payment WHERE id = ?").param(payment)
                .query(LocalDate.class).single();
        assertThat(businessDay).as("00:30 is before the 05:00 cutoff: the previous business day")
                .isEqualTo(halfPastMidnight.toLocalDate().minusDays(1));

        importCsv(mvc, sale.store(), card, line(confirmedAt(jdbc, payment), "25.00", "0", null, "A-BD1", "L1"),
                UUID.randomUUID().toString(), businessDay, businessDay, Permission.RECONCILIATION_MANAGE,
                Permission.RECONCILIATION_VIEW)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries.MATCHED").value(1));
    }
}
