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
    void aNewAccountMustChangeItsPasswordAndItsCreationIsAuditedWithoutThePassword() throws Exception {
        String username = unique("Maria.");

        UUID id = IdentityFixtures.id(mvc.perform(create(username, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username.toLowerCase()))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse());

        assertThat(jdbc.sql("SELECT password_hash FROM identity.user_account WHERE id = :id").param("id", id)
                .query(String.class).single()).startsWith("{bcrypt}");
        assertThat(jdbc.sql("SELECT details::text FROM audit.audit_event WHERE action = 'USER_CREATED' AND entity_id = :id")
                .param("id", id.toString()).query(String.class).single()).doesNotContain(PASSWORD);
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
    void anInitialPasswordMustBeStrong() throws Exception {
        mvc.perform(create(unique("u"), "short"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void managingAccountsNeedsAnInstallationWideUserManage() throws Exception {
        mvc.perform(post(USERS).with(as(Set.of(Permission.USER_MANAGE.authority(UUID.randomUUID())))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "initialPassword", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeHasAtMostOneAccount() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID employee = fixtures.employee("4821", fixtures.store(), IdentityFixtures.WAITER).id();
        mvc.perform(post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "initialPassword", PASSWORD, "employeeId", employee)))
                .andExpect(status().isCreated());

        mvc.perform(post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "initialPassword", PASSWORD, "employeeId", employee)))
                .andExpect(status().isConflict());
    }

    private static MockHttpServletRequestBuilder create(String username, String password) {
        return post(USERS).with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", username, "initialPassword", password,
                        "roleIds", Set.of(IdentityFixtures.MANAGER)));
    }
}
