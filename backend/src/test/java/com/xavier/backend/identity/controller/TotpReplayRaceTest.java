package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.MfaFixtures.verify;
import static com.xavier.backend.identity.MfaFixtures.verifyRecovery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D139: two sessions sending the same code (or recovery code) at the same moment get exactly one success. The test
 * holds the factor row locked until both requests are blocked behind it (pg_blocking_pids), then lets go.
 */
@MfaTest
class TotpReplayRaceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    MfaFixtures mfa;
    MfaFixtures.Enrolled enrolled;

    @BeforeEach
    void enrolled() throws Exception {
        mfa = new MfaFixtures(mvc, clock);
        enrolled = mfa.enrolledUser(Set.of(IdentityFixtures.WAITER));
        policy.set(MfaMode.ALL_BACKOFFICE);
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    @Test
    void oneCodeSentByTwoSessionsAtOnceLogsInOnlyOne() throws Exception {
        String code = mfa.nextCode(enrolled.secret());

        assertThat(race(verify(pending(), code), verify(pending(), code))).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void oneRecoveryCodeSentByTwoSessionsAtOnceLogsInOnlyOne() throws Exception {
        String code = enrolled.recoveryCodes().getFirst();

        assertThat(race(verifyRecovery(pending(), code), verifyRecovery(pending(), code)))
                .containsExactlyInAnyOrder(200, 401);
    }

    private List<Integer> race(MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            return new TransactionTemplate(transactions).execute(tx -> {
                jdbc.sql("SELECT id FROM identity.mfa_factor WHERE account_id = ? AND status = 'ACTIVE' FOR UPDATE")
                        .param(enrolled.user().id()).query(Object.class).single();
                Future<Integer> a = pool.submit(() -> mvc.perform(first).andReturn().getResponse().getStatus());
                Future<Integer> b = pool.submit(() -> mvc.perform(second).andReturn().getResponse().getStatus());
                awaitWaitersOnFactorLock(2);
                return List.of(a, b);
            }).stream().map(TotpReplayRaceTest::get).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Both requests are blocked behind the test's lock, directly or behind each other (pg_blocking_pids). */
    private void awaitWaitersOnFactorLock(int waiters) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND cardinality(pg_blocking_pids(pid)) > 0").query(Long.class).single() < waiters) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the requests never waited for the factor lock");
            }
            Thread.onSpinWait();
        }
    }

    private static Integer get(Future<Integer> status) {
        try {
            return status.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Cookie pending() throws Exception {
        return mvc.perform(IdentityFixtures.loginRequest(enrolled.user().username(), IdentityFixtures.STRONG_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }
}
