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
 * r2-decisions §12: BackOffice password guessing is throttled per username and client address (ten attempts inside a
 * fifteen-minute window block that pair for fifteen minutes) and per address across usernames (thirty failures).
 * Both answer 429. Another address is not blocked, so nobody can lock an administrator out from elsewhere.
 */
@IdentityTest
class LoginThrottlingHttpTest {

    static final String PASSWORD = "initial password 1";
    static final String WRONG = "not the password";
    /** Fresh per test: the per-address counter would otherwise add up across tests. */
    String attacker;
    String office;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    User user;

    @BeforeEach
    void user() throws Exception {
        user = new IdentityFixtures(mvc).newUser(PASSWORD, Set.of());
        attacker = randomAddress();
        office = randomAddress();
    }

    @Test
    void theEleventhBadPasswordInsideFifteenMinutesIsRefusedWith429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, attacker)).andExpect(status().isUnauthorized());
        }

        mvc.perform(login(WRONG, attacker))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));
        mvc.perform(login(PASSWORD, attacker)).andExpect(status().isTooManyRequests());
        assertThat(throttledAudits(user.id().toString())).isEqualTo(1);
    }

    @Test
    void aTenthAttemptThatSucceedsIsNotAuditedAsABlock() throws Exception {
        for (int i = 0; i < 9; i++) {
            mvc.perform(login(WRONG, attacker)).andExpect(status().isUnauthorized());
        }

        mvc.perform(login(PASSWORD, attacker)).andExpect(status().isOk());

        assertThat(throttledAudits(user.id().toString())).isZero();
    }

    @Test
    void sprayingPasswordsAcrossUsernamesFromOneAddressIsBlockedAfterThirtyFailures() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        List<User> users = List.of(user, fixtures.newUser(PASSWORD, Set.of()), fixtures.newUser(PASSWORD, Set.of()),
                fixtures.newUser(PASSWORD, Set.of()));
        for (int i = 0; i < 30; i++) {
            mvc.perform(login(users.get(i % 4), WRONG, attacker)).andExpect(status().isUnauthorized());
        }

        mvc.perform(login(users.get(0), PASSWORD, attacker))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_THROTTLED"));
        mvc.perform(login(users.get(1), PASSWORD, attacker)).andExpect(status().isTooManyRequests());
        mvc.perform(login(users.get(0), PASSWORD, office)).andExpect(status().isOk());
        assertThat(throttledAudits(attacker)).isEqualTo(1);
    }

    @Test
    void aSuccessfulLoginStartsTheCountAgain() throws Exception {
        for (int i = 0; i < 9; i++) {
            mvc.perform(login(WRONG, office)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login(PASSWORD, office)).andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, office)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login(WRONG, office)).andExpect(status().isTooManyRequests());
    }

    @Test
    void aBlockedAddressDoesNotLockTheAccountOutElsewhere() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, attacker));
        }

        mvc.perform(login(PASSWORD, office)).andExpect(status().isOk());
        mvc.perform(login(PASSWORD, attacker)).andExpect(status().isTooManyRequests());
    }

    @Test
    void theBlockEndsFifteenMinutesAfterTheTenthFailure() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(login(WRONG, attacker));
        }
        mvc.perform(login(PASSWORD, attacker)).andExpect(status().isTooManyRequests());

        sixteenMinutesPass(attacker);

        mvc.perform(login(PASSWORD, attacker)).andExpect(status().isOk());
    }

    @Test
    void concurrentBadPasswordsNeverGetMoreThanTenTries() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(login(WRONG, attacker)).andReturn().getResponse().getStatus();
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

    private long throttledAudits(String entityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'LOGIN_THROTTLED' AND entity_id = :id""")
                .param("id", entityId).query(Long.class).single();
    }

    private static String randomAddress() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        return "10.%d.%d.%d".formatted(random.nextInt(256), random.nextInt(256), random.nextInt(1, 255));
    }

    private MockHttpServletRequestBuilder login(String password, String address) throws Exception {
        return login(user, password, address);
    }

    private static MockHttpServletRequestBuilder login(User who, String password, String address) throws Exception {
        return loginRequest(who.username(), password).with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }

    /**
     * Moves this user's pair count from {@code address} back in time; the database is the only place that remembers
     * it. Keys are stored as SHA-256 hex of {@code "pair:<username>|<address>"}.
     */
    private void sixteenMinutesPass(String address) throws Exception {
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("pair:" + user.username() + "|" + address).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        int moved = jdbc.sql("""
                        UPDATE identity.login_throttle
                           SET window_start = window_start - INTERVAL '16 minutes',
                               blocked_until = blocked_until - INTERVAL '16 minutes'
                         WHERE key = :key""")
                .param("key", key).update();
        assertThat(moved).isEqualTo(1);
    }
}
