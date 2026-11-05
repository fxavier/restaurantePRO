package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.MOVEMENTS;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P24 (changes R2): the cashier records an expense in their own session with {@code CASH_OPERATE}, and an approver
 * holding {@code CASH_EXPENSE} in the store authorizes it with code and PIN (D40), never the cashier themself.
 */
@CashModuleTest
class ExpenseApprovalTest {

    static final String PIN = "4826";

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    UUID store;
    UUID register;
    Employee manager;

    @BeforeEach
    void aStoreWithAManager() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        manager = new IdentityFixtures(mvc).employee(PIN, store, IdentityFixtures.MANAGER);
    }

    @Test
    void anExpenseIsRecordedWithAnApproverHoldingCashExpense() throws Exception {
        Pos cashier = pos(store);
        UUID session = fixtures.openSession(cashier, register, "50.00");

        String body = mvc.perform(expense(session, manager.code(), PIN).with(cashier.with(Permission.CASH_OPERATE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event
                WHERE action = 'CASH_MOVEMENT_RECORDED' AND entity_id = ? AND approver_id = ?""",
                CashFixtures.id(body).toString(), manager.id())).isOne();
    }

    @Test
    void anExpenseWithoutAValidApproverIsRefusedEvenIfTheCashierHoldsCashExpense() throws Exception {
        Pos cashier = pos(store);
        UUID session = fixtures.openSession(cashier, register, "50.00");
        Employee waiter = new IdentityFixtures(mvc).employee(PIN, store, IdentityFixtures.WAITER);

        mvc.perform(expense(session, null, null).with(cashier.with(Permission.CASH_OPERATE, Permission.CASH_EXPENSE)))
                .andExpect(status().isForbidden());
        mvc.perform(expense(session, waiter.code(), PIN).with(cashier.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
        mvc.perform(expense(session, manager.code(), "0000").with(cashier.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND type = 'EXPENSE'",
                session)).isZero();
    }

    @Test
    void theCashierCannotApproveTheirOwnExpense() throws Exception {
        Pos managerAtTheTill = new Pos(manager.id(), UUID.randomUUID(), store);
        UUID session = fixtures.openSession(managerAtTheTill, register, "50.00");

        mvc.perform(expense(session, manager.code(), PIN)
                        .with(managerAtTheTill.with(Permission.CASH_OPERATE, Permission.CASH_EXPENSE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
    }

    @Test
    void cashInAndOutNeedNoApprover() throws Exception {
        Pos cashier = pos(store);
        UUID session = fixtures.openSession(cashier, register, "50.00");

        mvc.perform(idem(post(MOVEMENTS)).with(cashier.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sessionId": "%s", "type": "CASH_OUT", "amount": 10, "reason": "safe drop"}"""
                                .formatted(session)))
                .andExpect(status().isCreated());
    }

    private static MockHttpServletRequestBuilder expense(UUID session, String approverCode, String pin) {
        MockHttpServletRequestBuilder request = idem(post(MOVEMENTS)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sessionId": "%s", "type": "EXPENSE", "amount": 5.00, "reason": "bread"}"""
                        .formatted(session));
        if (approverCode != null) {
            request.header("X-Approver-Code", approverCode).header("X-Approver-Pin", pin);
        }
        return request;
    }
}
