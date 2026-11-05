package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_CLOSINGS;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D79 (2), D91, A26: approval re-computes what the register should hold. When an adjustment changed it after the
 * closing was submitted, the approval is refused with 409 {@code CLOSING_STALE}; the manager rejects, and the next
 * attempt carries the new expected amount.
 */
@CashModuleTest
class ClosingStaleTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    UUID store;
    UUID register;
    Pos operator;
    UUID session;
    UUID day;
    UUID closing;

    @BeforeEach
    void aSubmittedClosingShortOfFive() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "95.00");
        day = fixtures.dayOf(session);
        closing = fixtures.submittedClosing(operator, register, day);
    }

    @Test
    void anAdjustmentBetweenSubmitAndApproveMakesTheApprovalStale() throws Exception {
        adjustOut("5.00");

        approve().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSING_STALE"));

        mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/reject"))
                        .with(account(store, Permission.CASH_CLOSING_APPROVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "an adjustment came in"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        UUID retry = fixtures.submittedClosing(operator, register, day);
        mvc.perform(idem(post(BO_CLOSINGS + "/" + retry + "/approve"))
                        .with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.lines[0].expected.amount").value(95.00))
                .andExpect(jsonPath("$.lines[0].difference.amount").value(0.00));
    }

    @Test
    void anUnchangedClosingIsApproved() throws Exception {
        approve().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private ResultActions approve() throws Exception {
        return mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE)));
    }

    private void adjustOut(String amount) throws Exception {
        mvc.perform(idem(post("/api/v1/backoffice/cash/sessions/" + session + "/adjustments"))
                        .with(account(store, Permission.CASH_ADJUST)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "OUT", "methodCode": "CASH", "amount": %s, "reason": "unrecorded expense"}"""
                                .formatted(amount)))
                .andExpect(status().isCreated());
    }
}
