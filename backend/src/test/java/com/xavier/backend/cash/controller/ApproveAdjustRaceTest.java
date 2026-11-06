package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D79 (2), D91: an approval and an adjustment of the same register and day, fired at the same instant, serialize on
 * the day's lock. Either the approval wins and the adjustment is refused (422 {@code CLOSING_ALREADY_APPROVED}), or
 * the adjustment wins and the approval is stale (409 {@code CLOSING_STALE}); never both.
 */
@CashModuleTest
class ApproveAdjustRaceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @RepeatedTest(3)
    void anApprovalAndAnAdjustmentNeverBothSucceed() throws Exception {
        UUID store = fixtures.newStore();
        UUID register = fixtures.newRegister(store);
        Pos operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "100.00");
        UUID closing = fixtures.submittedClosing(operator, register, fixtures.dayOf(session));

        List<Integer> statuses = CashFixtures.race(
                () -> mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                        .with(account(store, Permission.CASH_CLOSING_APPROVE))).andReturn().getResponse().getStatus(),
                () -> mvc.perform(idem(post("/api/v1/backoffice/cash/sessions/" + session + "/adjustments"))
                        .with(account(store, Permission.CASH_ADJUST)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction": "OUT", "methodCode": "CASH", "amount": 5, "reason": "late expense"}"""))
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).isIn(List.of(200, 422), List.of(409, 201));
        long adjustments = fixtures.count(
                "SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND type = 'ADJUSTMENT'", session);
        long approved = fixtures.count("SELECT count(*) FROM cash.cash_closing WHERE id = ? AND status = 'APPROVED'",
                closing);
        assertThat(adjustments + approved).as("one of the two, never both").isOne();
    }
}
