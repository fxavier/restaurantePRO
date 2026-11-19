package com.xavier.bff;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static com.xavier.bff.BffExpectedTenantTest.EXPECTED_TENANT;
import static com.xavier.bff.BffTenantSelectionTest.TENANT_A;
import static com.xavier.bff.BffTenantSelectionTest.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FE-F22: a write is bound to the user the tab was loaded for, not only to its company. The BFF session is one per
 * browser, so a second user of the same company logging in from another tab takes it over; tab 1's write must not be
 * accepted as theirs. The user is the Keycloak subject, the only user id the BFF knows.
 */
class BffExpectedUserTest extends BffTestSupport {

    static final String EXPECTED_USER = "X-Expected-User";
    static final String RELAYED_USER = "X-Relayed-User";

    @Test
    void aWriteOfATabLoadedForAnotherUserOfTheSameCompanyIsRefusedWithoutReachingTheBackend() throws Exception {
        MockHttpSession browser = login();
        AUTHORIZATION_SERVER.logInAs("user-2");
        login(browser); // U2, a member of the same company, logs in from another tab

        mvc.perform(post("/api/v1/backoffice/stores").session(browser).with(xsrf())
                        .header(EXPECTED_TENANT, TENANT_A).header(EXPECTED_USER, "user-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CHANGED"))
                .andExpect(header().doesNotExist(RELAYED_USER));

        assertThat(BACKEND.received()).isEmpty();
    }

    /** A stale tab of U1 must not choose the company for U2, who logged in on the same session meanwhile. */
    @Test
    void aTenantChoiceOfATabLoadedForAnotherUserIsRefusedAndNothingIsStored() throws Exception {
        MockHttpSession browser = login();
        AUTHORIZATION_SERVER.logInAs("user-2");
        login(browser);

        mvc.perform(put("/bff/tenant").session(browser).with(xsrf()).header(EXPECTED_USER, "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + TENANT_B + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CHANGED"));
        mvc.perform(get("/api/v1/backoffice/stores").session(browser)).andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().headers()).doesNotContainKey("x-tenant-id");
    }

    @Test
    void aTenantChoiceNamingTheLoggedInUserIsStored() throws Exception {
        MockHttpSession session = login();

        mvc.perform(put("/bff/tenant").session(session).with(xsrf()).header(EXPECTED_USER, "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + TENANT_B + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().header("X-Tenant-Id")).isEqualTo(TENANT_B);
    }

    @Test
    void aWriteExpectingTheLoggedInUserIsRelayedWithoutTheHeaderAndNamesTheUser() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf()).header(EXPECTED_USER, "user-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RELAYED_USER, "user-1"));

        assertThat(BACKEND.onlyRequest().headers()).doesNotContainKey("x-expected-user");
    }

    /** Before its first session read a tab is not pinned and names nobody; it holds nothing of another user. */
    @Test
    void aWriteNamingNoUserIsRelayed() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf())).andExpect(status().isOk());

        assertThat(BACKEND.received()).hasSize(1);
    }

    @Test
    void aReadIsNotCheckedButItsAnswerNamesTheLoggedInUser() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/v1/backoffice/stores").session(session).header(EXPECTED_USER, "someone-else"))
                .andExpect(status().isOk())
                .andExpect(header().string(RELAYED_USER, "user-1"));
        mvc.perform(get("/api/v1/auth/tenants").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string(RELAYED_USER, "user-1"));
    }

    @Test
    void theRelayedUserNeverComesFromTheBackend() throws Exception {
        BACKEND.willRespond(200, Map.of("Content-Type", "application/json", RELAYED_USER, "user-9"), "{}");
        MockHttpSession session = login();

        mvc.perform(get("/api/v1/backoffice/stores").session(session))
                .andExpect(header().stringValues(RELAYED_USER, "user-1"));
    }
}
