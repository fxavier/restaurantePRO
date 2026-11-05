package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.POS_DAYS;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * spec §4 "Dia comercial pode atravessar a meia-noite" and §8.4 "Dia não fecha operacionalmente com sessões ou
 * pedidos pendentes": the store's current cash day is the oldest one still OPEN, so the cashier who worked past the
 * cutoff still sees, and closes, the day the work belongs to. With no OPEN day it is the day of today's business date.
 * The store's cutoff is 05:00 in Africa/Maputo (UTC+2): 03:00Z.
 */
@CashModuleTest
class CurrentDayAcrossCutoffTest {

    static final Instant BEFORE_CUTOFF = Instant.parse("2026-11-05T02:59:00Z"); // 04:59 local: business day 4 Nov
    static final Instant AFTER_CUTOFF = Instant.parse("2026-11-05T03:01:00Z");  // 05:01 local: business day 5 Nov
    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashTestClock clock;

    @Autowired
    CashApi cash;

    @Autowired
    TransactionTemplate tx;

    UUID store;
    Pos night;
    UUID nightSession;

    @BeforeEach
    void aSessionOpenedJustBeforeTheCutoff() throws Exception {
        store = fixtures.newStore(LocalTime.of(5, 0));
        night = pos(store);
        clock.set(BEFORE_CUTOFF);
        nightSession = fixtures.openSession(night, fixtures.newRegister(store), "10.00");
    }

    @AfterEach
    void realTime() {
        clock.release();
        SecurityContextHolder.clearContext();
    }

    @Test
    void beforeTheCutoffTheCurrentDayIsTheDayOfTheSession() throws Exception {
        current().andExpect(status().isOk()).andExpect(jsonPath("$.businessDate").value("2026-11-04"));
    }

    @Test
    void pastTheCutoffTheDayStillOpenStaysCurrent() throws Exception {
        clock.set(AFTER_CUTOFF);

        current().andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-11-04"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void aMorningSessionOpensTheNewDayButTheOlderOpenDayIsClosedFirst() throws Exception {
        clock.set(AFTER_CUTOFF);
        fixtures.openSession(pos(store), fixtures.newRegister(store), "10.00");

        current().andExpect(status().isOk()).andExpect(jsonPath("$.businessDate").value("2026-11-04"));

        closeNightDay();
        current().andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-11-05"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void withNoOpenDayTheCurrentDayIsTodaysBusinessDateWhateverItsState() throws Exception {
        closeNightDay();
        current().andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-11-04"))
                .andExpect(jsonPath("$.status").value("OPERATIONALLY_CLOSED"));

        clock.set(AFTER_CUTOFF);
        current().andExpect(status().isNotFound());
    }

    private ResultActions current() throws Exception {
        return mvc.perform(get(POS_DAYS + "/current").with(pos(store).with(Permission.CASH_VIEW)));
    }

    private void closeNightDay() {
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(night.actor(Permission.CASH_OPERATE)));
        tx.executeWithoutResult(status -> {
            UUID day = cash.lockSession(nightSession).cashDayId();
            cash.closeSession(nightSession, cash.lockSession(nightSession).version(),
                    List.of(new MethodCount("CASH", Money.of("10.00", MZN))), night.employeeId());
            cash.closeDayOperationally(day, cash.findDay(day).orElseThrow().version(), night.employeeId(),
                    ActorKind.EMPLOYEE);
        });
        SecurityContextHolder.clearContext();
    }
}
