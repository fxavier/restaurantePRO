package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.BO_DAYS;
import static com.xavier.backend.cash.CashFixtures.POS_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.POS_DAYS;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * spec §8.3 CashDay: the operational close (settlement, through CashApi) and the financial close are distinct, and
 * a reopen (P19) is an authorized event from OPERATIONALLY_CLOSED back to OPEN. Each test starts with a store whose
 * one register had one closed, balanced session today.
 */
@CashModuleTest
class DayHttpTest {

    static final String PIN = "4826";
    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashApi cash;

    @Autowired
    TransactionTemplate tx;

    UUID store;
    UUID register;
    Pos operator;
    UUID day;

    @BeforeEach
    void closedSession() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "10.00");
        asOperator(() -> {
            day = cash.lockSession(session).cashDayId();
            cash.closeSession(session, cash.lockSession(session).version(),
                    List.of(new MethodCount("CASH", Money.of("10.00", MZN))), operator.employeeId());
        });
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void thePosReadsTheStoresCurrentCashDay() throws Exception {
        mvc.perform(get(POS_DAYS + "/current").with(pos(store).with(Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(day.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get(POS_DAYS + "/current").with(pos(fixtures.newStore()).with(Permission.CASH_VIEW)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theFinancialCloseNeedsEveryClosingOfTheDayApproved() throws Exception {
        closeOperationally();
        mvc.perform(financialClose()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLOSINGS_NOT_APPROVED"));

        UUID closing = submittedClosing();
        mvc.perform(financialClose()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLOSINGS_NOT_APPROVED"));

        mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE))).andExpect(status().isOk());
        mvc.perform(financialClose())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINANCIALLY_CLOSED"));
    }

    @Test
    void anOpenDayIsNotClosedFinanciallyAndOnlyAnApproverOfTheStoreMayTry() throws Exception {
        mvc.perform(financialClose()).andExpect(status().isConflict());
        mvc.perform(idem(post(BO_DAYS + "/" + day + "/financial-close"))
                .with(account(store, Permission.CASH_DAY_CLOSE))).andExpect(status().isForbidden());
        mvc.perform(idem(post(BO_DAYS + "/" + UUID.randomUUID() + "/financial-close"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE))).andExpect(status().isNotFound());
    }

    @Test
    void aManagerApprovedReopenReturnsTheDayToOpenAndIsRecorded() throws Exception {
        closeOperationally();
        Employee manager = approver(IdentityFixtures.MANAGER);

        mvc.perform(reopen(manager.code(), PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_day_reopening
                WHERE cash_day_id = ? AND approver_employee_id = ? AND reason = 'forgotten receipt'""",
                day, manager.id())).isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event
                WHERE action = 'CASH_DAY_REOPENED' AND entity_id = ? AND approver_id = ?""",
                day.toString(), manager.id())).isEqualTo(1);
        // P25: the reopened day takes corrections only (NewDayPolicyTest)
        mvc.perform(idem(post(CashFixtures.SESSIONS)).with(pos(store).with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CashFixtures.openSessionBody(fixtures.newRegister(store), "1.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DAY_CLOSING_ONLY"));
    }

    @Test
    void aReopenWithoutAValidApproverIsRefused() throws Exception {
        closeOperationally();
        Employee waiter = approver(IdentityFixtures.WAITER);

        mvc.perform(idem(post(BO_DAYS + "/" + day + "/reopen")).with(account(store, Permission.CASH_DAY_CLOSE))
                        .contentType(MediaType.APPLICATION_JSON).content(reason()))
                .andExpect(status().isForbidden());
        mvc.perform(reopen(waiter.code(), PIN)).andExpect(status().isForbidden());
        mvc.perform(reopen(approver(IdentityFixtures.MANAGER).code(), "0000")).andExpect(status().isForbidden());
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_day_reopening WHERE cash_day_id = ?", day))
                .isZero();
    }

    /** A FINANCIALLY_CLOSED day reopens too, but with CASH_DAY_REOPEN (D79 (3), FinancialReopenTest). */
    @Test
    void anOpenDayDoesNotReopenAndAFinanciallyClosedOneNeedsMoreThanCashDayClose() throws Exception {
        Employee manager = approver(IdentityFixtures.MANAGER);

        mvc.perform(reopen(manager.code(), PIN)).andExpect(status().isConflict());

        closeOperationally();
        UUID closing = submittedClosing();
        mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE))).andExpect(status().isOk());
        mvc.perform(financialClose()).andExpect(status().isOk());

        mvc.perform(reopen(manager.code(), PIN)).andExpect(status().isForbidden());
    }

    @Test
    void reopeningNeedsCashDayCloseInTheDaysStore() throws Exception {
        closeOperationally();
        Employee manager = approver(IdentityFixtures.MANAGER);

        mvc.perform(idem(post(BO_DAYS + "/" + day + "/reopen")).with(account(store, Permission.CASH_CLOSING_APPROVE))
                        .header("X-Approver-Code", manager.code()).header("X-Approver-Pin", PIN)
                        .contentType(MediaType.APPLICATION_JSON).content(reason()))
                .andExpect(status().isForbidden());
    }

    private void closeOperationally() {
        asOperator(() -> cash.closeDayOperationally(day, cash.findDay(day).orElseThrow().version(),
                operator.employeeId(), ActorKind.EMPLOYEE));
    }

    private UUID submittedClosing() throws Exception {
        UUID closing = CashFixtures.id(mvc.perform(idem(post(POS_CLOSINGS)).with(operator.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerId": "%s", "cashDayId": "%s"}""".formatted(register, day)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(idem(post(POS_CLOSINGS + "/" + closing + "/submit")).with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isOk());
        return closing;
    }

    private Employee approver(UUID role) throws Exception {
        return new IdentityFixtures(mvc).employee(PIN, store, role);
    }

    private MockHttpServletRequestBuilder financialClose() {
        return idem(post(BO_DAYS + "/" + day + "/financial-close")).with(account(store, Permission.CASH_CLOSING_APPROVE));
    }

    private MockHttpServletRequestBuilder reopen(String approverCode, String pin) {
        return idem(post(BO_DAYS + "/" + day + "/reopen")).with(account(store, Permission.CASH_DAY_CLOSE))
                .header("X-Approver-Code", approverCode).header("X-Approver-Pin", pin)
                .contentType(MediaType.APPLICATION_JSON).content(reason());
    }

    private static String reason() {
        return """
                {"reason": "forgotten receipt"}""";
    }

    private void asOperator(Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(operator.actor(Permission.CASH_OPERATE)));
        try {
            tx.executeWithoutResult(status -> action.run());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
