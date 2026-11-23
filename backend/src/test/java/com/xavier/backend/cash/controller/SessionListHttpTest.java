package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.pos;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.cash.CashModuleTest;
import com.xavier.backend.cash.CashTestClock;
import com.xavier.backend.identity.IdentityFixtures;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The Financeiro screen's session list: a store's cash sessions, newest first, filtered by business date and status. */
@CashModuleTest
class SessionListHttpTest {

    static final String BO_SESSIONS = "/api/v1/backoffice/cash/sessions";

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    @Autowired
    CashTestClock clock;

    UUID store;

    @BeforeEach
    void setUp() throws Exception {
        store = fixtures.newStore();
    }

    @AfterEach
    void realTime() {
        clock.release();
    }

    @Test
    void aStoresSessionsAreListedWithRegisterOperatorTimesStatusAndCounts() throws Exception {
        UUID register = fixtures.newRegister(store);
        Pos operator = pos(store);
        UUID session = fixtures.openSession(operator, register, "50.00");
        fixtures.closeSession(operator, session, "75.00");

        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString())
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(session.toString()))
                .andExpect(jsonPath("$.content[0].registerId").value(register.toString()))
                .andExpect(jsonPath("$.content[0].registerCode").isString())
                .andExpect(jsonPath("$.content[0].operatorEmployeeId").value(operator.employeeId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.content[0].businessDate").isString())
                .andExpect(jsonPath("$.content[0].openedAt").isString())
                .andExpect(jsonPath("$.content[0].closedAt").isString())
                .andExpect(jsonPath("$.content[0].openingFloat.amount").value("50.00"))
                .andExpect(jsonPath("$.content[0].counted[0].methodCode").value("CASH"))
                .andExpect(jsonPath("$.content[0].counted[0].amount.amount").value("75.00"));
    }

    /** The reader needs only CASH_VIEW: the operator's name comes with the row, not from the employees read. */
    @Test
    void eachRowNamesItsOperatorForAReaderWithoutEmployeeAccess() throws Exception {
        IdentityFixtures.Employee ana = new IdentityFixtures(mvc).employee("1234", store, IdentityFixtures.WAITER);
        UUID named = fixtures.openSession(new Pos(ana.id(), UUID.randomUUID(), store), fixtures.newRegister(store),
                "0");
        UUID unknown = fixtures.openSession(pos(store), fixtures.newRegister(store), "0");

        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString())
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')].operatorName".formatted(named)).value("Ana"))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].operatorName".formatted(unknown))
                        .value(contains(nullValue())));
    }

    @Test
    void theListIsFilteredByStatusAndBusinessDateAndPaged() throws Exception {
        store = fixtures.newStore(LocalTime.of(5, 0)); // 10:00Z is 12:00 in Maputo: that day's business date
        clock.set(Instant.parse("2026-11-02T10:00:00Z"));
        Pos early = pos(store);
        UUID closedEarlier = fixtures.openSession(early, fixtures.newRegister(store), "1.00");
        fixtures.closeSession(early, closedEarlier, "1.00");
        clock.set(Instant.parse("2026-11-03T10:00:00Z"));
        UUID open = fixtures.openSession(pos(store), fixtures.newRegister(store), "2.00");
        clock.set(Instant.parse("2026-11-03T10:05:00Z"));
        UUID alsoOpen = fixtures.openSession(pos(store), fixtures.newRegister(store), "3.00");

        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString()).param("status", "OPEN")
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", contains(alsoOpen.toString(), open.toString())));
        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString()).param("from", "2026-11-01")
                        .param("to", "2026-11-02").with(account(store, Permission.CASH_VIEW)))
                .andExpect(jsonPath("$.content[*].id", contains(closedEarlier.toString())));
        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString()).param("size", "1").param("page", "1")
                        .with(account(store, Permission.CASH_VIEW)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.content[*].id", contains(open.toString())));
    }

    @Test
    void listingNeedsCashViewInThatStore() throws Exception {
        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString())
                        .with(account(fixtures.newStore(), Permission.CASH_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO_SESSIONS).param("storeId", store.toString())
                        .with(account(store, Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
    }
}
