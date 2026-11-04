package com.xavier.backend.cash.controller;

import static com.xavier.backend.cash.CashFixtures.REGISTERS;
import static com.xavier.backend.cash.CashFixtures.account;
import static com.xavier.backend.cash.CashFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@CashModuleTest
class RegisterHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CashFixtures fixtures;

    UUID store;

    @BeforeEach
    void newStore() throws Exception {
        store = fixtures.newStore();
    }

    @Test
    void aRegisterManagerCreatesListsAndRenamesARegister() throws Exception {
        UUID register = fixtures.newRegister(store);

        mvc.perform(get(REGISTERS).param("storeId", store.toString())
                        .with(account(store, Permission.CASH_REGISTER_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(register.toString()))
                .andExpect(jsonPath("$.content[0].active").value(true));

        mvc.perform(idem(put(REGISTERS + "/" + register)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("BAR", false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BAR"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        UUID register = fixtures.newRegister(store);
        mvc.perform(idem(put(REGISTERS + "/" + register)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                .contentType(MediaType.APPLICATION_JSON).content(update("A1", true, 0))).andExpect(status().isOk());

        mvc.perform(idem(put(REGISTERS + "/" + register)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("A2", true, 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void registerCodesAreUniqueWithinAStore() throws Exception {
        String body = """
                {"storeId": "%s", "code": "MAIN", "name": "Caixa"}""".formatted(store);
        mvc.perform(idem(post(REGISTERS)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

        mvc.perform(idem(post(REGISTERS)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void anotherStoresManagerIsRefusedAndAnUnknownRegisterIsNotFound() throws Exception {
        UUID register = fixtures.newRegister(store);
        UUID other = fixtures.newStore();

        mvc.perform(get(REGISTERS).param("storeId", store.toString())
                .with(account(other, Permission.CASH_REGISTER_MANAGE))).andExpect(status().isForbidden());
        mvc.perform(idem(put(REGISTERS + "/" + register)).with(account(other, Permission.CASH_REGISTER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("X", true, 0)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(put(REGISTERS + "/" + UUID.randomUUID())).with(account(store, Permission.CASH_REGISTER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("X", true, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void writesNeedAnIdempotencyKey() throws Exception {
        mvc.perform(post(REGISTERS).with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .with(account(store, Permission.CASH_REGISTER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "K", "name": "Caixa"}""".formatted(store)))
                .andExpect(status().isBadRequest());
    }

    static String update(String code, boolean active, long version) {
        return """
                {"code": "%s", "name": "Caixa", "active": %s, "version": %d}""".formatted(code, active, version);
    }
}
