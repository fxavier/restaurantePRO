package com.xavier.backend.settlement.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.FakePaymentProvider;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Payment methods are company-wide data (D52) managed in the BackOffice with PAYMENT_METHOD_MANAGE. */
@SettlementModuleTest
class PaymentMethodHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void cashIsSeededAndANewExternalTerminalMethodIsListed() throws Exception {
        String code = SettlementFixtures.freshCode("CARD");
        fixtures.method(code, "EXTERNAL_TERMINAL", null);

        mvc.perform(get(SettlementFixtures.METHODS).with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'CASH')].kind").value("CASH"))
                .andExpect(jsonPath("$[?(@.code == '%s')].kind".formatted(code)).value("EXTERNAL_TERMINAL"))
                .andExpect(jsonPath("$[?(@.code == '%s')].active".formatted(code)).value(true));
    }

    @Test
    void anIntegratedMethodNeedsAProviderBean() throws Exception {
        mvc.perform(create(SettlementFixtures.freshCode("PAY"), "INTEGRATED", "\"NO_SUCH_PROVIDER\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_AVAILABLE"));
        mvc.perform(create(SettlementFixtures.freshCode("PAY"), "INTEGRATED", "\"" + FakePaymentProvider.CODE + "\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerCode").value(FakePaymentProvider.CODE));
    }

    @Test
    void onlyTheSeededMethodIsCash() throws Exception {
        mvc.perform(create(SettlementFixtures.freshCode("CASH"), "CASH", "null"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_METHOD_KIND"));
    }

    @Test
    void aTakenCodeIs409() throws Exception {
        String code = SettlementFixtures.freshCode("MM");
        fixtures.method(code, "EXTERNAL_TERMINAL", null);

        mvc.perform(create(code, "EXTERNAL_TERMINAL", "null")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_CODE_TAKEN"));
    }

    @Test
    void aMethodIsRenamedAndDeactivatedAtItsVersion() throws Exception {
        JsonNode method = fixtures.method(SettlementFixtures.freshCode("CARD"), "EXTERNAL_TERMINAL", null);
        String path = SettlementFixtures.METHODS + "/" + method.get("id").asString();

        mvc.perform(put(path).with(manager()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Visa\", \"active\": false, \"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Visa"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).with(manager()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Visa\", \"active\": true, \"version\": 0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void managingMethodsNeedsTheInstallationWidePermission() throws Exception {
        mvc.perform(get(SettlementFixtures.METHODS).with(CashFixtures.account(null, Permission.CASH_VIEW)))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder create(String code, String kind, String provider) {
        return post(SettlementFixtures.METHODS).with(manager()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "%s", "name": "%s", "kind": "%s", "providerCode": %s}""".formatted(code, code, kind,
                        provider));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return CashFixtures.account(null, Permission.PAYMENT_METHOD_MANAGE);
    }
}
