package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.MOVEMENTS;
import static com.xavier.backend.cash.CashFixtures.SESSIONS;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@CashModuleTest
class MovementHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    UUID store;
    Pos operator;
    UUID session;

    @BeforeEach
    void openSession() throws Exception {
        store = fixtures.newStore();
        operator = pos(store);
        session = fixtures.openSession(operator, fixtures.newRegister(store), "100.00");
    }

    @Test
    void cashInAndCashOutChangeWhatTheDrawerShouldHold() throws Exception {
        mvc.perform(movement(operator, "CASH_IN", "20.00", "change from the bank").with(
                        operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CASH_IN"))
                .andExpect(jsonPath("$.methodCode").value("CASH"));
        mvc.perform(movement(operator, "CASH_OUT", "30.00", "safe drop").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isCreated());

        mvc.perform(get(SESSIONS + "/current").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(jsonPath("$.expected[0].amount.amount").value(90.00));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = ? AND store_id = ?",
                "CASH_MOVEMENT_RECORDED", store)).isEqualTo(2);
    }

    @Test
    void anExpenseNeedsCashExpenseAsWell() throws Exception {
        mvc.perform(movement(operator, "EXPENSE", "5.00", "bread").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());

        mvc.perform(movement(operator, "EXPENSE", "5.00", "bread")
                        .with(operator.with(Permission.CASH_OPERATE, Permission.CASH_EXPENSE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EXPENSE"));
    }

    @Test
    void movementsGoOnlyIntoTheOperatorsOwnSession() throws Exception {
        Pos colleague = pos(store);

        mvc.perform(movement(colleague, "CASH_IN", "5.00", "x").with(colleague.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
        Pos elsewhere = pos(fixtures.newStore());
        mvc.perform(movement(elsewhere, "CASH_IN", "5.00", "x").with(elsewhere.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyManualTypesWithAPositiveAmountAndAReasonAreAccepted() throws Exception {
        mvc.perform(movement(operator, "PAYMENT_RECEIPT", "5.00", "x").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isBadRequest());
        mvc.perform(movement(operator, "OPENING_FLOAT", "5.00", "x").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isBadRequest());
        mvc.perform(movement(operator, "CASH_IN", "0", "x").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isBadRequest());
        mvc.perform(movement(operator, "CASH_IN", "10000000000000000", "x")
                .with(operator.with(Permission.CASH_OPERATE))).andExpect(status().isBadRequest());
        mvc.perform(movement(operator, "CASH_IN", "5.00", " ").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder movement(Pos who, String type, String amount, String reason) {
        return idem(post(MOVEMENTS)).contentType(MediaType.APPLICATION_JSON).content("""
                {"sessionId": "%s", "type": "%s", "amount": %s, "reason": "%s"}"""
                .formatted(session, type, amount, reason));
    }
}
