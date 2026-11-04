package com.xavier.backend.organization.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.organization.OrganizationModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@OrganizationModuleTest
class CompanyProfileHttpTest {

    static final String URL = "/api/v1/backoffice/company-profile";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void anAdminReadsTheCompanyProfile() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("MZN"))
                .andExpect(jsonPath("$.country").value("MZ"))
                .andExpect(jsonPath("$.name").isString())
                .andExpect(jsonPath("$.taxId").isString())
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    @WithActor(permissions = Permission.STORE_MANAGE)
    void readingTheProfileNeedsCompanyManage() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void anAdminUpdatesTheProfileAndTheChangeIsAudited() throws Exception {
        JsonNode current = currentProfile();
        long auditsBefore = audits("COMPANY_PROFILE_UPDATED");
        String newName = "Renamed " + UUID.randomUUID();

        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(newName, current.get("taxId").asString(), current.get("currency").asString(),
                                current.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.version").value(current.get("version").asLong() + 1));

        assertThat(audits("COMPANY_PROFILE_UPDATED")).isEqualTo(auditsBefore + 1);
        assertThat(jdbc.sql("""
                SELECT actor_id FROM audit.audit_event WHERE action = 'COMPANY_PROFILE_UPDATED'
                ORDER BY occurred_at DESC LIMIT 1""").query(UUID.class).single())
                .isEqualTo(UUID.fromString(WithActor.DEFAULT_USER_ID));
    }

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void anUpdateBasedOnAStaleVersionIsAConflict() throws Exception {
        JsonNode current = currentProfile();
        String taxId = current.get("taxId").asString();
        String currency = current.get("currency").asString();
        long version = current.get("version").asLong();
        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("First " + UUID.randomUUID(), taxId, currency, version)))
                .andExpect(status().isOk());

        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Second " + UUID.randomUUID(), taxId, currency, version)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void aCurrencyThatCannotHoldMoneyIsRejected() throws Exception {
        JsonNode current = currentProfile();

        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Sol", "1", "XXX", current.get("version").asLong())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void aMissingFieldIsAValidationError() throws Exception {
        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxId\": \"1\", \"country\": \"MZ\", \"currency\": \"MZN\", \"version\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @WithActor(permissions = Permission.COMPANY_MANAGE)
    void thereIsNoEndpointToCreateACompany() throws Exception {
        mvc.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Second company", "1", "MZN", 0)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithActor(permissions = Permission.STORE_MANAGE)
    void updatingTheProfileNeedsCompanyManage() throws Exception {
        mvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Sol", "1", "MZN", 0)))
                .andExpect(status().isForbidden());
    }

    private JsonNode currentProfile() throws Exception {
        String json = mvc.perform(get(URL)).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(json);
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action")
                .param("action", action).query(Long.class).single();
    }

    static String body(String name, String taxId, String currency, long version) {
        return """
                {"name": "%s", "taxId": "%s", "country": "MZ", "currency": "%s", "version": %d}
                """.formatted(name, taxId, currency, version);
    }
}
