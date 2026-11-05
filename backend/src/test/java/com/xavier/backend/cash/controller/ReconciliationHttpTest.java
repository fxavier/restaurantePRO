package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D79 (7), D87: the cash reconciliation is read from the closings and movements themselves, per day × register ×
 * method: what the register should hold now (adjustments included), what was counted, the adjustments, the
 * difference, and the latest closing's justification, status and round.
 */
@CashModuleTest
class ReconciliationHttpTest {

    static final String RECONCILIATION = "/api/v1/backoffice/cash/reconciliation";

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashApi cash;

    UUID store;
    UUID register;
    UUID session;
    UUID closing;
    LocalDate businessDate;

    @BeforeEach
    void aClosingShortOfFiveThenAnAdjustment() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        Pos operator = pos(store);
        session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "95.00");
        businessDate = cash.sessionDay(session).businessDate();
        closing = fixtures.submittedClosing(operator, register, fixtures.dayOf(session));
        mvc.perform(idem(post("/api/v1/backoffice/cash/sessions/" + session + "/adjustments"))
                        .with(account(store, Permission.CASH_ADJUST)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "OUT", "methodCode": "CASH", "amount": 3.00, "reason": "bread"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void eachDayRegisterAndMethodShowsExpectedCountedAdjustmentsAndTheClosing() throws Exception {
        reconciliation(store, businessDate, businessDate, Permission.CASH_VIEW)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].businessDate").value(businessDate.toString()))
                .andExpect(jsonPath("$[0].dayStatus").value("OPEN"))
                .andExpect(jsonPath("$[0].registerId").value(register.toString()))
                .andExpect(jsonPath("$[0].methodCode").value("CASH"))
                .andExpect(jsonPath("$[0].expected.amount").value(97.00))
                .andExpect(jsonPath("$[0].counted.amount").value(95.00))
                .andExpect(jsonPath("$[0].adjustments.amount").value(-3.00))
                .andExpect(jsonPath("$[0].difference.amount").value(-2.00))
                .andExpect(jsonPath("$[0].justification").value("counted again"))
                .andExpect(jsonPath("$[0].closingId").value(closing.toString()))
                .andExpect(jsonPath("$[0].closingStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].dayRound").value(0));
    }

    @Test
    void daysOutsideTheRangeAreLeftOut() throws Exception {
        reconciliation(store, businessDate.plusDays(1), businessDate.plusDays(9), Permission.CASH_VIEW)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void itNeedsCashViewInTheStoreAndAnOrderedRange() throws Exception {
        reconciliation(store, businessDate, businessDate, Permission.CASH_OPERATE).andExpect(status().isForbidden());
        reconciliation(fixtures.newStore(), businessDate, businessDate, Permission.CASH_VIEW)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(RECONCILIATION).param("storeId", store.toString()).param("from", businessDate.toString())
                        .param("to", businessDate.minusDays(1).toString())
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(RECONCILIATION).param("storeId", store.toString())
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isBadRequest());
        reconciliation(store, businessDate.minusDays(366), businessDate, Permission.CASH_VIEW)
                .andExpect(status().isOk());
        reconciliation(store, businessDate.minusDays(367), businessDate, Permission.CASH_VIEW)
                .andExpect(status().isBadRequest());
    }

    private ResultActions reconciliation(UUID storeId, LocalDate from, LocalDate to, Permission permission)
            throws Exception {
        return mvc.perform(get(RECONCILIATION).param("storeId", storeId.toString()).param("from", from.toString())
                .param("to", to.toString()).with(account(storeId, permission)));
    }
}
