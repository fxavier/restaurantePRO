package com.xavier.bff;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BffTenantSelectionTest extends BffTestSupport {

    static final String TENANT_A = "0193a1b2-0000-7000-8000-00000000000a";
    static final String TENANT_B = "0193a1b2-0000-7000-8000-00000000000b";

    @Test
    void theSelectedTenantIsAddedToEveryRelayedCall() throws Exception {
        MockHttpSession session = login();

        select(session, TENANT_A);
        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/tenants").session(session)).andExpect(status().isOk());

        assertThat(BACKEND.received()).hasSize(2).allSatisfy(r -> assertThat(r.header("X-Tenant-Id")).isEqualTo(TENANT_A));
    }

    @Test
    void withoutASelectionNoTenantHeaderIsSent() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/v1/backoffice/stores").session(session).header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().headers()).doesNotContainKey("x-tenant-id");
    }

    @Test
    void theBrowserCannotOverrideTheSessionSelection() throws Exception {
        MockHttpSession session = login();
        select(session, TENANT_A);

        mvc.perform(get("/api/v1/backoffice/stores").session(session).header("X-Tenant-Id", TENANT_B))
                .andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().header("X-Tenant-Id")).isEqualTo(TENANT_A);
    }

    @Test
    void switchingTenantChangesTheRelayedHeader() throws Exception {
        MockHttpSession session = login();
        select(session, TENANT_A);
        select(session, TENANT_B);

        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().header("X-Tenant-Id")).isEqualTo(TENANT_B);
    }

    @Test
    void aMalformedTenantIdIsRefusedAndKeepsThePreviousSelection() throws Exception {
        MockHttpSession session = login();
        select(session, TENANT_A);

        for (String body : new String[] {"{\"tenantId\":\"not-a-uuid\"}", "{}", "{\"tenantId\":\"a\\r\\nX-Evil: 1\"}"}) {
            mvc.perform(put("/bff/tenant").session(session).with(xsrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/backoffice/stores").session(session)).andExpect(status().isOk());

        assertThat(BACKEND.onlyRequest().header("X-Tenant-Id")).isEqualTo(TENANT_A);
    }

    @Test
    void selectingATenantNeedsALogin() throws Exception {
        mvc.perform(put("/bff/tenant").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + TENANT_A + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void select(MockHttpSession session, String tenantId) throws Exception {
        mvc.perform(put("/bff/tenant").session(session).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isNoContent());
    }
}
