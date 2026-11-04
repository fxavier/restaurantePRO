package com.xavier.backend.identity.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.LoginThrottledException;
import com.xavier.backend.identity.repository.LoginThrottleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BackOffice password throttling (r2-decisions §12). The key is the username together with the client address:
 * {@value #MAX_FAILURES} attempts inside {@link #WINDOW} block that pair for {@link #BLOCK} (429), and the block is
 * audited once. A username alone would let anyone keep an administrator locked out indefinitely by sending ten bad
 * passwords every fifteen minutes; with the pair, the administrator still logs in from their own address.
 *
 * <p>Every attempt is counted before its password is checked, in one atomic upsert, so concurrent guesses never get
 * more than {@value #MAX_FAILURES} checks; a successful login deletes the count. Each call commits on its own:
 * a refused login rolls nothing back.
 */
@Service
class LoginThrottling {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK = Duration.ofMinutes(15);
    /** {@code login_throttle.key} length. */
    private static final int MAX_KEY_LENGTH = 120;

    private static final Logger log = LoggerFactory.getLogger(LoginThrottling.class);

    private final LoginThrottleRepository throttles;
    private final AuditLog auditLog;
    private final Clock clock;

    LoginThrottling(LoginThrottleRepository throttles, AuditLog auditLog, Clock clock) {
        this.throttles = throttles;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * @param username  normalized
     * @param accountId the account with that username, or {@code null} if none (for the audit row)
     * @throws LoginThrottledException while the pair is blocked
     */
    @Transactional
    public void countAttempt(String username, String clientAddress, UUID accountId) {
        Instant now = clock.instant();
        String key = key(username, clientAddress);
        List<Integer> counted = throttles.countAttempt(key, now, now.minus(WINDOW), MAX_FAILURES, now.plus(BLOCK));
        if (counted.isEmpty()) {
            Instant blockedUntil = throttles.findById(key).map(t -> t.getBlockedUntil()).orElse(now);
            throw new LoginThrottledException(Duration.between(now, blockedUntil));
        }
        if (counted.getFirst() == MAX_FAILURES) {
            log.warn("BackOffice login throttled for username '{}' from {} for {}", username, clientAddress, BLOCK);
            auditLog.recordAsSystem("LOGIN_THROTTLED", "UserAccount",
                    accountId == null ? "unknown-username" : accountId.toString(), null, null, null,
                    Map.of("clientAddress", clientAddress, "attempts", MAX_FAILURES, "blockMinutes", BLOCK.toMinutes()));
        }
    }

    @Transactional
    public void succeeded(String username, String clientAddress) {
        throttles.deleteById(key(username, clientAddress));
    }

    /**
     * Hourly: deletes counts whose window started more than {@code WINDOW + BLOCK} ago; any block they held has
     * ended, so the next attempt would start again at 1 anyway.
     *
     * @return how many were deleted
     */
    @Scheduled(cron = "0 7 * * * *")
    @Transactional
    public int purgeExpired() {
        int deleted = throttles.deleteWindowsStartedBefore(clock.instant().minus(WINDOW).minus(BLOCK));
        log.info("Login throttle purge deleted {} expired counts", deleted);
        return deleted;
    }

    private static String key(String username, String clientAddress) {
        String key = username + "|" + clientAddress;
        return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
    }
}
