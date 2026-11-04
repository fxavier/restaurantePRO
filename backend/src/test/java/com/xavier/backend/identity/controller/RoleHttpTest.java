package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IdentityTest
class RoleHttpTest {

    static final String ROLES = "/api/v1/backoffice/roles";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRoleIsANamedSetOfPermissionsAndItsCreationIsAudited() throws Exception {
        String name = unique("Sommelier");

        UUID id = IdentityFixtures.id(mvc.perform(post(ROLES).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", name, "permissions", List.of("DINING_VIEW", "CATALOG_VIEW"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("DINING_VIEW", "CATALOG_VIEW")))
                .andReturn().getResponse());

        mvc.perform(get(ROLES + "/" + id).with(admin())).andExpect(jsonPath("$.name").value(name));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'ROLE_CREATED' AND entity_id = :id")
                .param("id", id.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theSeededRolesAreListed() throws Exception {
        mvc.perform(get(ROLES).param("size", "200").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.name == 'Empregado de sala')].permissions[*]",
                        containsInAnyOrder("DINING_VIEW", "CATALOG_VIEW")));
    }

    @Test
    void aRoleNameIsUnique() throws Exception {
        String name = unique("Runner");
        create(name);

        mvc.perform(post(ROLES).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", name, "permissions", List.of())))
                .andExpect(status().isConflict());
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        UUID id = create(unique("Host"));
        long read = IdentityFixtures.JSON.readTree(mvc.perform(get(ROLES + "/" + id).with(admin()))
                .andReturn().getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put(ROLES + "/" + id).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("Host"), "permissions", List.of("DINING_VIEW"), "version", read)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(read + 1));

        mvc.perform(put(ROLES + "/" + id).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("Host"), "permissions", List.of(), "version", read)))
                .andExpect(status().isConflict());
    }

    @Test
    void theAdministratorRoleCannotBeEdited() throws Exception {
        mvc.perform(put(ROLES + "/" + IdentityFixtures.ADMINISTRATOR).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Administrador", "permissions", List.of(), "version", 0)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void managingRolesNeedsAnInstallationWideRoleManage() throws Exception {
        UUID store = UUID.randomUUID();

        mvc.perform(post(ROLES).with(as(Set.of(Permission.ROLE_MANAGE.authority(store)))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("X"), "permissions", List.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nobodyAddsToARolePermissionsTheyDoNotHold() throws Exception {
        UUID role = IdentityFixtures.id(mvc.perform(post(ROLES).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("RoleAdmin"), "permissions", List.of("ROLE_MANAGE"))))
                .andExpect(status().isCreated()).andReturn().getResponse());
        long version = IdentityFixtures.JSON.readTree(mvc.perform(get(ROLES + "/" + role).with(admin()))
                .andReturn().getResponse().getContentAsString()).get("version").asLong();

        mvc.perform(put(ROLES + "/" + role).with(as(Set.of(Permission.ROLE_MANAGE.authority()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("RoleAdmin"), "permissions", List.of("ROLE_MANAGE", "USER_MANAGE"),
                                "version", version)))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROLES).with(as(Set.of(Permission.ROLE_MANAGE.authority()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("X"), "permissions", List.of("USER_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPermissionIsABadRequest() throws Exception {
        mvc.perform(post(ROLES).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("X"), "permissions", List.of("FLY"))))
                .andExpect(status().isBadRequest());
    }

    private UUID create(String name) throws Exception {
        return IdentityFixtures.id(mvc.perform(post(ROLES).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", name, "permissions", List.of("DINING_VIEW"))))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }
}
