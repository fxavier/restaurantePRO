package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The role editor's catalogue: every code-defined permission, whether it needs a step-up, and its area. */
@IdentityTest
class PermissionCatalogueHttpTest {

    static final String PERMISSIONS = "/api/v1/backoffice/permissions";

    @Autowired
    MockMvc mvc;

    @Test
    void theCatalogueListsEveryPermissionWithItsSensitivityAndArea() throws Exception {
        mvc.perform(get(PERMISSIONS).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(Permission.values().length)))
                .andExpect(jsonPath("$[?(@.name == 'ROLE_MANAGE')].sensitive").value(true))
                .andExpect(jsonPath("$[?(@.name == 'ORDER_VIEW')].sensitive").value(false))
                .andExpect(jsonPath("$[?(@.name == 'CASH_CLOSING_APPROVE')].area").value("CASH"))
                .andExpect(jsonPath("$[?(@.name == 'TABLE_LOCK')].area").value("TABLE"));
    }

    @Test
    void readingTheCatalogueNeedsAnInstallationWideRoleManage() throws Exception {
        mvc.perform(get(PERMISSIONS).with(as(Set.of(Permission.USER_MANAGE.authority()))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PERMISSIONS).with(as(Set.of(Permission.ROLE_MANAGE.authority(UUID.randomUUID())))))
                .andExpect(status().isForbidden());
    }
}
