package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.BO_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.POS_CLOSINGS;
import static com.xavier.backend.cash.CashFixtures.SESSIONS;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.openSessionBody;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.ReceiptCommand;
import com.xavier.backend.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import tools.jackson.databind.JsonNode;

/**
 * spec §8.3 CashClosing and §8.4 "Fecho de caixa considera todas as sessões da caixa". Each test starts with one
 * register whose only session took a 100.00 float, a 50.00 cash and a 30.00 card receipt, and was counted at
 * 145.00 cash and 30.00 card: the cash line is 5.00 short.
 */
@CashModuleTest
class ClosingHttpTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashApi cash;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    UUID store;
    UUID register;
    Pos operator;
    UUID day;

    @BeforeEach
    void countedSession() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "100.00");
        SecurityContextHolder.getContext().setAuthentication(
                CashFixtures.authenticationOf(operator.actor(Permission.CASH_OPERATE)));
        tx.executeWithoutResult(status -> {
            cash.recordReceipt(new ReceiptCommand(session, "CASH", money("50.00"), UUID.randomUUID(),
                    operator.employeeId()));
            cash.recordReceipt(new ReceiptCommand(session, "CARD", money("30.00"), UUID.randomUUID(),
                    operator.employeeId()));
            day = cash.lockSession(session).cashDayId();
            cash.closeSession(session, cash.lockSession(session).version(),
                    List.of(new MethodCount("CASH", money("145.00")), new MethodCount("CARD", money("30.00"))),
                    operator.employeeId());
        });
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aDraftComparesTheMovementsWithTheCountPerMethod() throws Exception {
        mvc.perform(create(register))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.lines[0].methodCode").value("CARD"))
                .andExpect(jsonPath("$.lines[0].difference.amount").value(0.00))
                .andExpect(jsonPath("$.lines[1].methodCode").value("CASH"))
                .andExpect(jsonPath("$.lines[1].expected.amount").value(150.00))
                .andExpect(jsonPath("$.lines[1].counted.amount").value(145.00))
                .andExpect(jsonPath("$.lines[1].difference.amount").value(-5.00));
    }

    @Test
    void repeatingADraftWithTheSameKeyReplaysItsLines() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(create(register, key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String replay = mvc.perform(create(register, key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(CashFixtures.json(replay)).isEqualTo(CashFixtures.json(first));
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_closing WHERE register_id = ?", register))
                .isEqualTo(1);
    }

    @Test
    void approversListTheStoresClosingsByDayAndStatus() throws Exception {
        UUID submitted = submittedClosing();

        mvc.perform(get(BO_CLOSINGS).param("storeId", store.toString()).param("status", "SUBMITTED")
                        .with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(submitted.toString()))
                .andExpect(jsonPath("$.content[0].lines.length()").value(2));
        mvc.perform(get(BO_CLOSINGS).param("storeId", store.toString()).param("cashDayId", day.toString())
                        .param("status", "APPROVED").with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BO_CLOSINGS).param("storeId", store.toString()).with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get(BO_CLOSINGS).param("storeId", store.toString()).with(account(store, Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO_CLOSINGS).param("storeId", store.toString())
                .with(account(fixtures.newStore(), Permission.CASH_CLOSING_APPROVE))).andExpect(status().isForbidden());
    }

    @Test
    void aRegisterWithASessionStillOpenCannotBeClosed() throws Exception {
        Pos late = pos(store);
        UUID otherRegister = fixtures.newRegister(store);
        fixtures.openSession(late, otherRegister, "1.00");

        mvc.perform(create(otherRegister))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SESSION_STILL_OPEN"));
    }

    @Test
    void aRegisterWithoutSessionsThatDayHasNothingToClose() throws Exception {
        mvc.perform(create(fixtures.newRegister(store)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_SESSIONS"));
    }

    @Test
    void aDifferenceMustBeJustifiedBeforeSubmission() throws Exception {
        UUID closing = createClosing();

        mvc.perform(submit(closing))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("JUSTIFICATION_REQUIRED"));
        mvc.perform(justify(closing, 0, "CASH", "coins short"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lines[1].justification").value("coins short"));
        mvc.perform(justify(closing, 0, "CASH", "again")).andExpect(status().isConflict());

        mvc.perform(submit(closing)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(justify(closing, 2, "CASH", "too late")).andExpect(status().isConflict());
    }

    @Test
    void approvingASubmittedClosingCountsItsDifferenceForTheStore() throws Exception {
        UUID closing = submittedClosing();
        double before = differenceTotal();

        mvc.perform(approve(closing).with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(differenceTotal() - before).isEqualTo(5.00);
        Counter counter = meters.get("cash.closing.difference").tag("store", store.toString()).counter();
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("store");
        mvc.perform(approve(closing).with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isConflict());
    }

    @Test
    void aRejectedClosingIsKeptAndTheNextAttemptSupersedesIt() throws Exception {
        UUID first = submittedClosing();

        mvc.perform(idem(post(BO_CLOSINGS + "/" + first + "/reject"))
                        .with(account(store, Permission.CASH_CLOSING_APPROVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "recount the coins"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("recount the coins"));

        JsonNode second = CashFixtures.json(mvc.perform(create(register))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.supersedesId").value(first.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());

        assertThat(second.get("id").asString()).isNotEqualTo(first.toString());
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_closing WHERE id = ? AND status = 'REJECTED'",
                first)).isEqualTo(1);
        mvc.perform(create(register)).andExpect(status().isConflict());
    }

    @Test
    void aRegisterBeingClosedOpensNoNewSessionThatDay() throws Exception {
        createClosing();

        mvc.perform(idem(post(SESSIONS)).with(pos(store).with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON).content(openSessionBody(register, "1.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REGISTER_CLOSED_FOR_DAY"));
    }

    @Test
    void approvalNeedsTheApprovePermissionInTheClosingsStore() throws Exception {
        UUID closing = submittedClosing();

        mvc.perform(approve(closing).with(account(store, Permission.CASH_OPERATE))).andExpect(status().isForbidden());
        mvc.perform(approve(closing).with(account(fixtures.newStore(), Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isForbidden());
        mvc.perform(approve(UUID.randomUUID()).with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isNotFound());
        mvc.perform(create(register).with(pos(fixtures.newStore()).with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
    }

    UUID createClosing() throws Exception {
        return CashFixtures.id(mvc.perform(create(register)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    UUID submittedClosing() throws Exception {
        UUID closing = createClosing();
        mvc.perform(justify(closing, 0, "CASH", "coins short")).andExpect(status().isOk());
        mvc.perform(submit(closing)).andExpect(status().isOk());
        return closing;
    }

    double differenceTotal() {
        Counter counter = meters.find("cash.closing.difference").tag("store", store.toString()).counter();
        return counter == null ? 0 : counter.count();
    }

    MockHttpServletRequestBuilder create(UUID registerId) {
        return create(registerId, UUID.randomUUID().toString());
    }

    MockHttpServletRequestBuilder create(UUID registerId, String key) {
        return idem(post(POS_CLOSINGS), key).with(operator.with(Permission.CASH_OPERATE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"registerId": "%s", "cashDayId": "%s"}""".formatted(registerId, day));
    }

    MockHttpServletRequestBuilder justify(UUID closing, long version, String method, String text) {
        return idem(put(POS_CLOSINGS + "/" + closing)).with(operator.with(Permission.CASH_OPERATE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"version": %d, "justifications": [{"methodCode": "%s", "text": "%s"}]}"""
                        .formatted(version, method, text));
    }

    MockHttpServletRequestBuilder submit(UUID closing) {
        return idem(post(POS_CLOSINGS + "/" + closing + "/submit")).with(operator.with(Permission.CASH_OPERATE));
    }

    static MockHttpServletRequestBuilder approve(UUID closing) {
        return idem(post(BO_CLOSINGS + "/" + closing + "/approve"));
    }

    static Money money(String amount) {
        return Money.of(amount, MZN);
    }
}
