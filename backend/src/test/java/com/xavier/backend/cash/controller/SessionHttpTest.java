package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.REGISTERS;
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
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@CashModuleTest
class SessionHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashTestClock clock;

    UUID store;
    UUID register;
    Pos operator;

    @BeforeEach
    void setUp() throws Exception {
        store = fixtures.newStore();
        register = fixtures.newRegister(store);
        operator = pos(store);
    }

    @AfterEach
    void realTime() {
        clock.release();
    }

    @Test
    void openingTheFirstSessionCreatesTheDayAndRecordsTheOpeningFloat() throws Exception {
        UUID session = CashFixtures.id(mvc.perform(open(operator, register, "100.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.registerId").value(register.toString()))
                .andExpect(jsonPath("$.operatorEmployeeId").value(operator.employeeId().toString()))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get(SESSIONS + "/current").with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.toString()))
                .andExpect(jsonPath("$.expected[0].methodCode").value("CASH"))
                .andExpect(jsonPath("$.expected[0].amount.amount").value(100.00));
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_day WHERE store_id = ?", store)).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE session_id = ? AND type = ?",
                session, "OPENING_FLOAT")).isEqualTo(1);
    }

    @Test
    void repeatingAnOpenWithTheSameKeyReplaysTheSessionAndOpensNothingMore() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(open(operator, register, "100.00", key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String replay = mvc.perform(open(operator, register, "100.00", key))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(CashFixtures.json(replay)).isEqualTo(CashFixtures.json(first));
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_session WHERE register_id = ?", register))
                .isEqualTo(1);
    }

    @Test
    void aZeroOpeningFloatRecordsNoMovement() throws Exception {
        UUID session = CashFixtures.id(mvc.perform(open(operator, register, "0"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_movement WHERE session_id = ?", session))
                .isZero();
    }

    @Test
    void aSecondOpenSessionOnTheSameRegisterIsAConflict() throws Exception {
        fixtures.openSession(operator, register, "10.00");

        mvc.perform(open(pos(store), register, "10.00")).andExpect(status().isConflict());
    }

    @Test
    void anOperatorWithAnOpenSessionCannotOpenASecondOne() throws Exception {
        fixtures.openSession(operator, register, "10.00");

        mvc.perform(open(operator, fixtures.newRegister(store), "10.00")).andExpect(status().isConflict());
    }

    @Test
    void concurrentOpensOnOneRegisterGiveOneSessionAndOneConflict() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID contested = fixtures.newRegister(store);
            Pos first = pos(store);
            Pos second = pos(store);
            List<Integer> statuses = CashFixtures.race(
                    () -> mvc.perform(open(first, contested, "5.00")).andReturn().getResponse().getStatus(),
                    () -> mvc.perform(open(second, contested, "5.00")).andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
            assertThat(fixtures.count("SELECT count(*) FROM cash.cash_session WHERE register_id = ?", contested))
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentFirstSessionsOfTheDayShareOneDay() throws Exception {
        UUID otherRegister = fixtures.newRegister(store);
        Pos other = pos(store);
        List<Integer> statuses = CashFixtures.race(
                () -> mvc.perform(open(operator, register, "5.00")).andReturn().getResponse().getStatus(),
                () -> mvc.perform(open(other, otherRegister, "5.00")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(201, 201);
        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_day WHERE store_id = ?", store)).isEqualTo(1);
    }

    @Test
    void theBusinessDayCrossesMidnightUntilTheStoreCutoff() throws Exception {
        UUID lateStore = fixtures.newStore(LocalTime.of(4, 0)); // Africa/Maputo, UTC+2
        clock.set(Instant.parse("2026-11-04T22:30:00Z")); // 00:30 on 5 November, local time

        mvc.perform(open(pos(lateStore), fixtures.newRegister(lateStore), "1.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-11-04"));

        clock.set(Instant.parse("2026-11-05T02:30:00Z")); // 04:30 local: the 5th has started
        mvc.perform(open(pos(lateStore), fixtures.newRegister(lateStore), "1.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessDate").value("2026-11-05"));
    }

    @Test
    void anInactiveRegisterCannotOpen() throws Exception {
        mvc.perform(idem(put(REGISTERS + "/" + register)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "OFF", "name": "Caixa", "active": false, "version": 0}"""))
                .andExpect(status().isOk());

        mvc.perform(open(operator, register, "1.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REGISTER_INACTIVE"));
    }

    @Test
    void anotherStoresRegisterAndABackOfficeSessionAreRefused() throws Exception {
        UUID foreignRegister = fixtures.newRegister(fixtures.newStore());

        mvc.perform(open(operator, foreignRegister, "1.00")).andExpect(status().isForbidden());
        mvc.perform(idem(post(SESSIONS)).with(account(store, Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON).content(openSessionBody(register, "1.00")))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(SESSIONS)).with(operator.with(Permission.CASH_VIEW))
                        .contentType(MediaType.APPLICATION_JSON).content(openSessionBody(register, "1.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAmountWithMoreDecimalsThanTheCurrencyIsRefused() throws Exception {
        mvc.perform(open(operator, register, "1.005"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(open(operator, register, "10000000000000000")).andExpect(status().isBadRequest());
    }

    @Test
    void aSessionIsReadWithCashViewInItsStoreOnly() throws Exception {
        UUID session = fixtures.openSession(operator, register, "20.00");

        mvc.perform(get(SESSIONS + "/" + session).with(pos(store).with(Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFloat.amount").value(20.00));
        mvc.perform(get(SESSIONS + "/" + session).with(pos(fixtures.newStore()).with(Permission.CASH_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(SESSIONS + "/" + UUID.randomUUID()).with(pos(store).with(Permission.CASH_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get(SESSIONS + "/current").with(pos(store).with(Permission.CASH_OPERATE)))
                .andExpect(status().isNotFound());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder open(
            Pos who, UUID register, String openingFloat) {
        return open(who, register, openingFloat, UUID.randomUUID().toString());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder open(
            Pos who, UUID register, String openingFloat, String key) {
        return idem(post(SESSIONS), key).with(who.with(Permission.CASH_OPERATE))
                .contentType(MediaType.APPLICATION_JSON).content(openSessionBody(register, openingFloat));
    }
}
