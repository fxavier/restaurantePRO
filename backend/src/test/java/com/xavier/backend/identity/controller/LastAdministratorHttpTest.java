package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.User;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The installation always keeps one active account with the administrator role. The rule is about the whole
 * table, and the test database is shared: each test parks the other active administrators (SQL) and restores
 * them afterwards. Test classes run one after the other, so nothing else sees the parked state.
 */
@IdentityTest
class LastAdministratorHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    IdentityFixtures fixtures;
    List<UUID> parked;

    @BeforeEach
    void parkOtherAdministrators() {
        fixtures = new IdentityFixtures(mvc);
        parked = jdbc.sql("""
                        UPDATE identity.user_account u SET active = false
                        WHERE active AND EXISTS (SELECT 1 FROM identity.user_account_role r
                            WHERE r.user_account_id = u.id AND r.role_id = :admin)
                        RETURNING id""")
                .param("admin", IdentityFixtures.ADMINISTRATOR).query(UUID.class).list();
    }

    @AfterEach
    void restoreThem() {
        if (!parked.isEmpty()) {
            jdbc.sql("UPDATE identity.user_account SET active = true WHERE id IN (:ids)").param("ids", parked)
                    .update();
        }
    }

    @Test
    void theLastActiveAdministratorCanBeNeitherDemotedNorDeactivated() throws Exception {
        User first = fixtures.newUser("initial password 1", Set.of(IdentityFixtures.ADMINISTRATOR));
        User last = fixtures.newUser("initial password 1", Set.of(IdentityFixtures.ADMINISTRATOR));

        mvc.perform(demote(first)).andExpect(status().isOk());

        mvc.perform(demote(last)).andExpect(status().isUnprocessableContent());
        mvc.perform(put("/api/v1/backoffice/users/" + last.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(IdentityFixtures.ADMINISTRATOR), "active", false,
                                "version", version(last))))
                .andExpect(status().isUnprocessableContent());
    }

    /** A support account never holds USER_MANAGE (D83), so it could not appoint a new administrator. */
    @Test
    void aSupportAccountWithTheAdministratorRoleDoesNotCountAsAnotherAdministrator() throws Exception {
        User last = fixtures.newUser("initial password 1", Set.of(IdentityFixtures.ADMINISTRATOR));
        mvc.perform(post("/api/v1/backoffice/users").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", IdentityFixtures.unique("sup"), "initialPassword",
                                "initial password 1", "roleIds", Set.of(IdentityFixtures.ADMINISTRATOR),
                                "support", true)))
                .andExpect(status().isCreated());

        mvc.perform(demote(last)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void twoConcurrentDemotionsCannotBothRemoveTheLastTwoAdministrators() throws Exception {
        User one = fixtures.newUser("initial password 1", Set.of(IdentityFixtures.ADMINISTRATOR));
        User other = fixtures.newUser("initial password 1", Set.of(IdentityFixtures.ADMINISTRATOR));
        MockHttpServletRequestBuilder demoteOne = demote(one);
        MockHttpServletRequestBuilder demoteOther = demote(other);
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(race(start, demoteOne));
            Future<Integer> second = pool.submit(race(start, demoteOther));
            start.countDown();
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
    }

    private Callable<Integer> race(CountDownLatch start, MockHttpServletRequestBuilder request) {
        return () -> {
            start.await();
            return mvc.perform(request).andReturn().getResponse().getStatus();
        };
    }

    private MockHttpServletRequestBuilder demote(User user) throws Exception {
        return put("/api/v1/backoffice/users/" + user.id()).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("roleIds", Set.of(), "active", true, "version", version(user)));
    }

    private long version(User user) throws Exception {
        return IdentityFixtures.JSON.readTree(mvc.perform(get("/api/v1/backoffice/users/" + user.id()).with(admin()))
                .andReturn().getResponse().getContentAsString()).get("version").asLong();
    }
}
