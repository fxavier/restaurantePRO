package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_DAYS;
import static com.xavier.backend.cash.CashFixtures.SESSIONS;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * P28, P25, D79 (5) and (6): after the store's cutoff a session opens in the new business day even while an earlier
 * day is still OPEN, and the response warns {@code PREVIOUS_DAY_OPEN}. An earlier day, or a reopened one, takes no
 * new session: it can only be closed (422 {@code DAY_CLOSING_ONLY}). The clock is pinned; the instants are chosen
 * around each store's cutoff, including the two daylight-saving transitions of Europe/Lisbon.
 */
@CashModuleTest
class NewDayPolicyTest {

    static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    static final String PIN = "4826";

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashTestClock clock;

    @AfterEach
    void realTime() {
        clock.release();
    }

    @Test
    void pastTheCutoffWithYesterdayOpenASessionOpensInTheNewDayWithAWarning() throws Exception {
        UUID store = fixtures.newStore(LocalTime.of(5, 0)); // Maputo, UTC+2: the cutoff is 03:00Z
        clock.set(Instant.parse("2026-11-05T02:59:00Z"));
        open(store).andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-11-04"))
                .andExpect(jsonPath("$.warnings").isEmpty());

        clock.set(Instant.parse("2026-11-05T03:01:00Z"));
        open(store).andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-11-05"))
                .andExpect(jsonPath("$.warnings[0]").value("PREVIOUS_DAY_OPEN"));
    }

    @Test
    void anEarlierDayTakesNoNewSessionOnceALaterOneExists() throws Exception {
        UUID store = fixtures.newStore(LocalTime.of(5, 0));
        clock.set(Instant.parse("2026-11-05T04:00:00Z")); // 06:00 local: business day 5 Nov
        open(store).andExpect(status().isCreated()).andExpect(jsonPath("$.businessDate").value("2026-11-05"));

        fixtures.setCutoff(store, LocalTime.of(7, 0)); // now 06:00 local belongs to 4 Nov again
        open(store).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DAY_CLOSING_ONLY"));
    }

    @Test
    void aReopenedDayTakesCorrectionsOnlyNotNewSessions() throws Exception {
        UUID store = fixtures.newStore();
        Pos operator = pos(store);
        UUID session = fixtures.openSession(operator, fixtures.newRegister(store), "0");
        fixtures.closeSession(operator, session, "0");
        UUID day = fixtures.dayOf(session);
        fixtures.closeDayOperationally(operator, day);
        Employee manager = new IdentityFixtures(mvc).employee(PIN, store, IdentityFixtures.MANAGER);
        mvc.perform(idem(post(BO_DAYS + "/" + day + "/reopen")).with(account(store, Permission.CASH_DAY_CLOSE))
                        .header("X-Approver-Code", manager.code()).header("X-Approver-Pin", PIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "forgotten receipt"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));

        open(store).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DAY_CLOSING_ONLY"));
    }

    /** Spring forward, 29 Mar 2026: 01:00 WET jumps to 02:00 WEST, so a 01:30 cutoff starts the day at the jump. */
    @Test
    void whenTheClockJumpsOverTheCutoffTheNewDayStartsAtTheJump() throws Exception {
        UUID store = fixtures.newStore(LocalTime.of(1, 30), LISBON);
        clock.set(Instant.parse("2026-03-29T00:59:00Z")); // 00:59 WET
        open(store).andExpect(jsonPath("$.businessDate").value("2026-03-28"));

        clock.set(Instant.parse("2026-03-29T01:00:00Z")); // 02:00 WEST
        open(store).andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-03-29"))
                .andExpect(jsonPath("$.warnings[0]").value("PREVIOUS_DAY_OPEN"));
    }

    /** Fall back, 25 Oct 2026: 01:30 happens twice, and the day starts at the first, 01:30 WEST = 00:30Z. */
    @Test
    void whenTheCutoffHappensTwiceTheNewDayStartsAtTheFirst() throws Exception {
        UUID store = fixtures.newStore(LocalTime.of(1, 30), LISBON);
        clock.set(Instant.parse("2026-10-25T00:29:00Z")); // 01:29 WEST
        open(store).andExpect(jsonPath("$.businessDate").value("2026-10-24"));

        clock.set(Instant.parse("2026-10-25T00:31:00Z")); // 01:31 WEST, before the clock goes back
        open(store).andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-10-25"))
                .andExpect(jsonPath("$.warnings[0]").value("PREVIOUS_DAY_OPEN"));
    }

    /** A new operator on a new register of {@code store} opens a session. */
    private ResultActions open(UUID store) throws Exception {
        Pos operator = pos(store);
        return mvc.perform(idem(post(SESSIONS)).with(operator.with(Permission.CASH_OPERATE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(CashFixtures.openSessionBody(fixtures.newRegister(store), "0")));
    }
}
