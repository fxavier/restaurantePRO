package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.ACCOUNT_ID;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * D79 (1), A26: a later correction is an audited ADJUSTMENT movement on a CLOSED session whose day is not
 * FINANCIALLY_CLOSED (spec §8.3 "correções posteriores são movimentos auditados; não reescrever contagem original").
 * It is a BackOffice command, {@code CASH_ADJUST} in the session's store.
 */
@CashModuleTest
class CashAdjustmentTest {

    static final String ADJUSTMENTS = "/api/v1/backoffice/cash/sessions/%s/adjustments";

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

    @BeforeEach
    void aClosedSessionShortOfFive() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
        session = fixtures.openSession(operator, register, "100.00");
        fixtures.closeSession(operator, session, "95.00");
    }

    @Test
    void anAdjustmentOnAClosedSessionChangesWhatTheRegisterShouldHoldAndIsAudited() throws Exception {
        mvc.perform(adjust(session, "OUT", "CASH", "5.00", "unrecorded bread expense")
                        .with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.sessionId").value(session.toString()))
                .andExpect(jsonPath("$.amount.amount").value(5.00));

        assertThat(jdbc.sql("""
                        SELECT sum(CASE WHEN direction = 'IN' THEN amount ELSE -amount END) FROM cash.cash_movement
                        WHERE session_id = ?""").param(session).query(String.class).single())
                .isEqualTo("95.0000");
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_movement
                WHERE session_id = ? AND type = 'ADJUSTMENT' AND actor_account_id = ? AND actor_employee_id IS NULL
                  AND reason = 'unrecorded bread expense'""", session, ACCOUNT_ID)).isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM cash.cash_session_count WHERE session_id = ? AND amount = 95.00""", session))
                .as("the stored count is never rewritten").isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event
                WHERE action = 'CASH_ADJUSTMENT_RECORDED' AND store_id = ? AND reason = 'unrecorded bread expense'
                  AND details @> '{"direction": "OUT", "methodCode": "CASH", "amount": "5.00"}'::jsonb""", store))
                .isOne();
    }

    @Test
    void anAdjustmentMayBeOfAnyMethodAndInEitherDirection() throws Exception {
        mvc.perform(adjust(session, "IN", "CARD", "12.50", "card slip found")
                        .with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.methodCode").value("CARD"));
    }

    @Test
    void anOpenSessionTakesANormalMovementInstead() throws Exception {
        UUID open = fixtures.openSession(pos(store), fixtures.newRegister(store), "0");

        mvc.perform(adjust(open, "IN", "CASH", "1.00", "x").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SESSION_STILL_OPEN"));
    }

    @Test
    void aFinanciallyClosedDayTakesNoAdjustment() throws Exception {
        UUID day = fixtures.dayOf(session);
        fixtures.closeDayOperationally(operator, day);
        UUID closing = fixtures.submittedClosing(operator, register, day);
        fixtures.approveClosing(store, closing);
        fixtures.closeDayFinancially(store, day);

        mvc.perform(adjust(session, "IN", "CASH", "1.00", "late").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DAY_FINANCIALLY_CLOSED"));
    }

    @Test
    void onlyCashAdjustInTheSessionsStoreMayAdjust() throws Exception {
        mvc.perform(adjust(session, "IN", "CASH", "1.00", "x").with(account(store, Permission.CASH_CLOSING_APPROVE)))
                .andExpect(status().isForbidden());
        mvc.perform(adjust(session, "IN", "CASH", "1.00", "x")
                        .with(account(fixtures.newStore(), Permission.CASH_ADJUST)))
                .andExpect(status().isForbidden());
        mvc.perform(adjust(UUID.randomUUID(), "IN", "CASH", "1.00", "x")
                        .with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAdjustmentNeedsADirectionAPositiveAmountAndAReason() throws Exception {
        mvc.perform(adjust(session, "SIDEWAYS", "CASH", "1.00", "x").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isBadRequest());
        mvc.perform(adjust(session, "IN", "CASH", "0", "x").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isBadRequest());
        mvc.perform(adjust(session, "IN", "CASH", "1.00", " ").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isBadRequest());
        mvc.perform(adjust(session, "IN", " ", "1.00", "x").with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReplayedAdjustmentIsRecordedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(adjust(session, "IN", "CASH", "2.00", "coin found", key)
                        .with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(adjust(session, "IN", "CASH", "2.00", "coin found", key)
                        .with(account(store, Permission.CASH_ADJUST)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(CashFixtures.id(replay)).isEqualTo(CashFixtures.id(first));
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND type = 'ADJUSTMENT'",
                session)).isOne();
    }

    @Test
    void theDatabaseTakesAnAdjustmentOnlyOnAClosedSessionWithAReasonAndOneActor() throws Exception {
        UUID open = fixtures.openSession(pos(store), fixtures.newRegister(store), "0");

        assertThatThrownBy(() -> insertAdjustment(open, "x", "actor_account_id"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("takes no adjustment");
        assertThatThrownBy(() -> insertAdjustment(session, null, "actor_account_id"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_manual_reason_ck");
        assertThatThrownBy(() -> insertAdjustment(session, "x", "NULL"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("cash_movement_one_actor_ck");
        insertAdjustment(session, "x", "actor_account_id");
    }

    private void insertAdjustment(UUID sessionId, String reason, String accountColumnOrNull) {
        jdbc.sql("""
                        INSERT INTO cash.cash_movement (id, store_id, cash_day_id, session_id, register_id, type,
                            direction, method_code, amount, currency, reason, actor_account_id, occurred_at)
                        SELECT uuidv7(), s.store_id, s.cash_day_id, s.id, s.register_id, 'ADJUSTMENT', 'IN', 'CASH', 1,
                            'MZN', :reason, %s, now()
                        FROM cash.cash_session s WHERE s.id = :session""".formatted(
                        "NULL".equals(accountColumnOrNull) ? "NULL" : "gen_random_uuid()"))
                .param("reason", reason).param("session", sessionId).update();
    }

    private static MockHttpServletRequestBuilder adjust(UUID sessionId, String direction, String method,
                                                        String amount, String reason) {
        return adjust(sessionId, direction, method, amount, reason, UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder adjust(UUID sessionId, String direction, String method,
                                                        String amount, String reason, String key) {
        return idem(post(ADJUSTMENTS.formatted(sessionId)), key).contentType(MediaType.APPLICATION_JSON).content("""
                {"direction": "%s", "methodCode": "%s", "amount": %s, "reason": "%s"}"""
                .formatted(direction, method, amount, reason));
    }
}
