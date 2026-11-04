package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.loginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.IdentityTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * r2-decisions §12: BackOffice password guessing is throttled per username and client address: ten failures inside
 * fifteen minutes block that pair for fifteen minutes (429). Another address is not blocked, so nobody can lock an
 * administrator out from elsewhere.
 */
@IdentityTest
class LoginThrottlingHttpTest {

    static final String PASSWORD = "initial password 1";
    static final String WRONG = "not the password";
    static final String ATTACKER = "10.0.0.66";
    static final String OFFICE = "10.0.0.10";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    User user;

    @BeforeEach
    void user() throws Exception {
        user = new IdentityFixtures(mvc).newUser(PASSWORD, Set.of());
    }

    @Test
    void theEleventhBadPasswordInsideFifteenMinutesIsRefusedWith429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, ATTACKER)).andExpect(status().isUnauthorized());
        }

        mvc.perform(login(WRONG, ATTACKER))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));
        mvc.perform(login(PASSWORD, ATTACKER)).andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'LOGIN_THROTTLED' AND entity_id = :id""")
                .param("id", user.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aSuccessfulLoginStartsTheCountAgain() throws Exception {
        for (int i = 0; i < 9; i++) {
            mvc.perform(login(WRONG, OFFICE)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login(PASSWORD, OFFICE)).andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, OFFICE)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login(WRONG, OFFICE)).andExpect(status().isTooManyRequests());
    }

    @Test
    void aBlockedAddressDoesNotLockTheAccountOutElsewhere() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, ATTACKER));
        }

        mvc.perform(login(PASSWORD, OFFICE)).andExpect(status().isOk());
        mvc.perform(login(PASSWORD, ATTACKER)).andExpect(status().isTooManyRequests());
    }

    @Test
    void theBlockEndsFifteenMinutesAfterTheTenthFailure() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, ATTACKER));
        }
        mvc.perform(login(PASSWORD, ATTACKER)).andExpect(status().isTooManyRequests());

        sixteenMinutesPass();

        mvc.perform(login(PASSWORD, ATTACKER)).andExpect(status().isOk());
    }

    @Test
    void concurrentBadPasswordsNeverGetMoreThanTenTries() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(login(WRONG, ATTACKER)).andReturn().getResponse().getStatus();
        };

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(15)) {
            List<Future<Integer>> futures = IntStream.range(0, 15).mapToObj(i -> pool.submit(attempt)).toList();
            start.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).filteredOn(s -> s == 401).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(5);
    }

    private MockHttpServletRequestBuilder login(String password, String address) throws Exception {
        return loginRequest(user.username(), password).with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    /** Moves this user's throttle back in time; the database is the only place that remembers it. */
    private void sixteenMinutesPass() {
        int moved = jdbc.sql("""
                        UPDATE identity.login_throttle
                           SET window_start = window_start - INTERVAL '16 minutes',
                               blocked_until = blocked_until - INTERVAL '16 minutes'
                         WHERE key LIKE :user""")
                .param("user", user.username() + "|%").update();
        assertThat(moved).isPositive();
    }
}
