package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IdentityTest
class UserHttpTest {

    static final String USERS = "/api/v1/backoffice/users";
    static final String PASSWORD = "initial password 1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aNewAccountHoldsNoCredentialAndItsCreationIsAudited() throws Exception {
        String username = unique("Maria.");

        UUID id = IdentityFixtures.id(mvc.perform(create(username, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username.toLowerCase()))
                .andExpect(jsonPath("$.mustChangePassword").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse());

        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'identity' "
                + "AND table_name = 'user_account' AND column_name LIKE '%password%'").query(Long.class).single())
                .as("Keycloak holds the credentials (D175)").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'USER_CREATED' AND entity_id = :id")
                .param("id", id.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aUsernameIsUniqueIgnoringCaseEvenUnderARace() throws Exception {
        String username = unique("joao");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(create(username, PASSWORD)).andReturn().getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        mvc.perform(create(username.toUpperCase(), PASSWORD)).andExpect(status().isConflict());
    }

    @Test
    void managingAccountsNeedsAnInstallationWideUserManage() throws Exception {
        mvc.perform(post(USERS).with(as(Set.of(Permission.USER_MANAGE.authority(UUID.randomUUID())))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void nobodyGivesAnAccountARoleWithPermissionsTheyDoNotHold() throws Exception {
        mvc.perform(post(USERS).with(as(Set.of(Permission.USER_MANAGE.authority()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"),
                                "roleIds", Set.of(IdentityFixtures.ADMINISTRATOR))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeHasAtMostOneAccount() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID employee = fixtures.employee("4821", fixtures.store(), IdentityFixtures.WAITER).id();
        mvc.perform(post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "employeeId", employee)))
                .andExpect(status().isCreated());

        mvc.perform(post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "employeeId", employee)))
                .andExpect(status().isConflict());
    }

    private static MockHttpServletRequestBuilder create(String username, String password) {
        return post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", username, "roleIds", Set.of(IdentityFixtures.MANAGER)));
    }
}
