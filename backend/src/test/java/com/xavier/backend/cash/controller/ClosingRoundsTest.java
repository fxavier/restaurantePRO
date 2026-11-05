package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.BO_DAYS;
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
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D79 (1)–(3): money signed off stays signed off. An APPROVED closing of the current round takes no adjustment
 * (the correction path is financial close → financial reopen → adjust → a closing in the next round); the financial
 * close needs, per register, the APPROVED closing of its latest round with the expected amount still current; and an
 * approval needs a day that is not FINANCIALLY_CLOSED and a closing of the day's current round.
 */
@CashModuleTest
class ClosingRoundsTest {

    static final String PIN = "4826";

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID register;
    Pos operator;
    UUID session;
    UUID day;
    UUID roundZero;

    @BeforeEach
    void anApprovedBalancedClosingOnAnOperationallyClosedDay() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "100.00");
        day = fixtures.dayOf(session);
        fixtures.closeDayOperationally(operator, day);
        roundZero = fixtures.submittedClosing(operator, register, day);
        fixtures.approveClosing(store, roundZero);
    }

    /** Scenario A: approved 100/100, then an adjustment of 20 would leave 80 vs 100 never justified. */
    @Test
    void anApprovedClosingOfTheCurrentRoundTakesNoAdjustment() throws Exception {
        adjustOut("20.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLOSING_ALREADY_APPROVED"));

        financialClose().andExpect(status().isOk());
    }

    /** Scenario A, backstop: whatever moved the expected amount, the financial close re-checks it. */
    @Test
    void theFinancialCloseRefusesAnApprovalWhoseExpectedAmountChanged() throws Exception {
        jdbc.sql("""
                        INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                            direction, method_code, amount, currency, reason, actor_account_id, occurred_at)
                        SELECT uuidv7(), store_id, cash_day_id, id, register_id, 'ADJUSTMENT', 'OUT', 'CASH', 20,
                            'MZN', 'out of band', gen_random_uuid(), now()
                        FROM cash.cash_session WHERE id = ?""").param(session).update();

        financialClose().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSING_STALE"));
    }

    /** Scenario B: after a financial reopen and an adjustment, the round-0 approval no longer signs the day off. */
    @Test
    void afterAnAdjustmentInRoundOneTheRoundZeroApprovalIsStale() throws Exception {
        financialClose().andExpect(status().isOk());
        reopen().andExpect(status().isOk());
        adjustOut("20.00").andExpect(status().isCreated());
        fixtures.closeDayOperationally(operator, day);

        financialClose().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSING_STALE"));

        UUID roundOne = fixtures.submittedClosing(operator, register, day);
        fixtures.approveClosing(store, roundOne);
        financialClose().andExpect(status().isOk());
    }

    /** Scenario C: a closing of an earlier round is never approved, nor anything on a FINANCIALLY_CLOSED day. */
    @Test
    void aClosingOfAnEarlierRoundOrOfAFinanciallyClosedDayIsNotApproved() throws Exception {
        financialClose().andExpect(status().isOk());
        UUID leftOver = submittedRoundZeroClosingOfAnotherRegister();

        approve(leftOver).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DAY_FINANCIALLY_CLOSED"));

        reopen().andExpect(status().isOk());
        approve(leftOver).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSING_STALE"));
    }

    /** A SUBMITTED round-0 closing no use case leaves behind, written straight to the table. */
    private UUID submittedRoundZeroClosingOfAnotherRegister() throws Exception {
        UUID other = fixtures.newRegister(store);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO cash.cash_closing (id, store_id, register_id, cash_day_id, attempt_no, status,
                            version, currency, created_at, created_by_employee_id, updated_at, submitted_at,
                            submitted_by_employee_id, day_round)
                        VALUES (?, ?, ?, ?, 1, 'SUBMITTED', 0, 'MZN', now(), ?, now(), now(), ?, 0)""")
                .params(id, store, other, day, operator.employeeId(), operator.employeeId()).update();
        return id;
    }

    private ResultActions approve(UUID closing) throws Exception {
        return mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE)));
    }

    private ResultActions financialClose() throws Exception {
        return mvc.perform(idem(post(BO_DAYS + "/" + day + "/financial-close"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE)));
    }

    private ResultActions reopen() throws Exception {
        Employee manager = new IdentityFixtures(mvc).employee(PIN, store, IdentityFixtures.MANAGER);
        return mvc.perform(idem(post(BO_DAYS + "/" + day + "/reopen")).with(account(store, Permission.CASH_DAY_REOPEN))
                .header("X-Approver-Code", manager.code()).header("X-Approver-Pin", PIN)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reason": "a refund was missed"}"""));
    }

    private ResultActions adjustOut(String amount) throws Exception {
        return mvc.perform(idem(post("/api/v1/backoffice/cash/sessions/" + session + "/adjustments"))
                .with(account(store, Permission.CASH_ADJUST)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"direction": "OUT", "methodCode": "CASH", "amount": %s, "reason": "unrecorded expense"}"""
                        .formatted(amount)));
    }
}
