package com.xavier.backend.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Throttle counts whose window and block have both ended are deleted, so guessed usernames do not pile up. */
@IdentityTest
class LoginThrottlePurgeTest {

    @Autowired
    LoginThrottling throttling;

    @Autowired
    JdbcClient jdbc;

    @Test
    void thePurgeDeletesOnlyCountsWhoseWindowAndBlockHaveEnded() {
        String ended = insert(Instant.now().minus(Duration.ofMinutes(31)), Instant.now().minus(Duration.ofMinutes(1)));
        String stillBlocked = insert(Instant.now().minus(Duration.ofMinutes(20)), Instant.now().plus(Duration.ofMinutes(5)));
        String counting = insert(Instant.now().minus(Duration.ofMinutes(2)), null);

        throttling.purgeExpired();

        assertThat(jdbc.sql("SELECT key FROM identity.login_throttle WHERE key IN (:keys)")
                .param("keys", java.util.List.of(ended, stillBlocked, counting)).query(String.class).list())
                .containsExactlyInAnyOrder(stillBlocked, counting);
    }

    private String insert(Instant windowStart, Instant blockedUntil) {
        String key = IdentityFixtures.unique("u") + "|10.0.0.1";
        jdbc.sql("""
                        INSERT INTO identity.login_throttle (key, failures, window_start, blocked_until)
                        VALUES (:key, :failures, :start, :blocked)""")
                .param("key", key).param("failures", blockedUntil == null ? 3 : 10)
                .param("start", Timestamp.from(windowStart))
                .param("blocked", blockedUntil == null ? null : Timestamp.from(blockedUntil))
                .update();
        return key;
    }
}
