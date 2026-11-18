package com.xavier.bff;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static com.xavier.bff.BffTenantSelectionTest.TENANT_A;
import static com.xavier.bff.BffTenantSelectionTest.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FE-F16: a write from a tab that still shows another company is refused instead of landing in the selected one. */
class BffExpectedTenantTest extends BffTestSupport {

    static final String EXPECTED_TENANT = "X-Expected-Tenant";

    @Test
    void aWriteExpectingAnotherTenantIsRefusedWithoutReachingTheBackend() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf())
                        .header(EXPECTED_TENANT, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TENANT_CHANGED"));

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void everyUnsafeMethodOnBothRelayedRoutesIsChecked() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(put("/api/v1/backoffice/stores/1").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/backoffice/stores/1").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/backoffice/stores/1").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/step-up").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isConflict());

        assertThat(BACKEND.received()).isEmpty();
    }

    /** Spring Security's firewall answers 400 to these before the relay; the relay's allow-list would refuse them too. */
    @Test
    void aLowercaseOrCustomVerbNeverReachesTheBackend() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        for (String method : new String[] {"post", "PURGE"}) {
            mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/backoffice/stores").session(session).with(xsrf())
                            .header(EXPECTED_TENANT, TENANT_A))
                    .andExpect(status().is4xxClientError());
        }

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void aWriteWithoutTheHeaderIsRefusedWhenTheSessionHasASelection() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_CHANGED"));

        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void aWriteExpectingTheSelectedTenantIsRelayedWithoutTheHeader() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf())
                        .header(EXPECTED_TENANT, TENANT_B.toUpperCase()))
                .andExpect(status().isOk());

        RecordedRequest relayed = BACKEND.onlyRequest();
        assertThat(relayed.header("X-Tenant-Id")).isEqualTo(TENANT_B);
        assertThat(relayed.headers()).doesNotContainKey("x-expected-tenant");
    }

    @Test
    void withoutASelectionAWriteIsRelayedAndTheHeaderStripped() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/backoffice/stores").session(session).with(xsrf()))
                .andExpect(status().isOk());

        assertThat(BACKEND.received()).hasSize(2)
                .allSatisfy(r -> assertThat(r.headers()).doesNotContainKeys("x-expected-tenant", "x-tenant-id"));
    }

    @Test
    void aReadIsNotChecked() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(get("/api/v1/backoffice/stores").session(session).header(EXPECTED_TENANT, TENANT_A))
                .andExpect(status().isOk());

        RecordedRequest relayed = BACKEND.onlyRequest();
        assertThat(relayed.header("X-Tenant-Id")).isEqualTo(TENANT_B);
        assertThat(relayed.headers()).doesNotContainKey("x-expected-tenant");
    }

    @Test
    void switchingTenantItselfIsNotChecked() throws Exception {
        MockHttpSession session = loginWithSelection(TENANT_B);

        mvc.perform(put("/bff/tenant").session(session).with(xsrf()).header(EXPECTED_TENANT, TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + TENANT_A + "\"}"))
                .andExpect(status().isNoContent());
    }

    private MockHttpSession loginWithSelection(String tenantId) throws Exception {
        MockHttpSession session = login();
        mvc.perform(put("/bff/tenant").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isNoContent());
        return session;
    }
}
