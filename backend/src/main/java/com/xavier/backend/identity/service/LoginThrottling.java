package com.xavier.backend.identity.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.LoginThrottledException;
import com.xavier.backend.identity.model.LoginThrottle;
import com.xavier.backend.identity.repository.LoginThrottleRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BackOffice password throttling (r2-decisions §12), two counters, both answering 429 with {@code Retry-After}:
 *
 * <ul>
 *   <li><b>Username + client address</b>: {@value #PAIR_MAX_ATTEMPTS} attempts block the pair for {@link #BLOCK}.
 *       Every attempt is counted before its password is checked, in one atomic upsert, so concurrent guesses never
 *       get more checks; a successful login deletes the count. There is deliberately no username-only counter: it
 *       would let anyone keep an administrator locked out indefinitely (ten bad passwords every fifteen minutes),
 *       while with the pair the administrator still logs in from their own address.</li>
 *   <li><b>Client address</b> across usernames, against password spraying: {@value #ADDRESS_MAX_FAILURES} refused
 *       logins block the address for {@link #BLOCK}. Failures only (successes are not counted and do not reset it),
 *       so an office behind one NAT address is not blocked by its own logins. Thirty is three accounts' worth of the
 *       pair limit: far above the typos of one company's BackOffice staff in fifteen minutes, while it caps spraying
 *       at two guesses a minute per address. Checked before and counted after the password check, so a concurrent
 *       burst can pass the limit by the requests already in flight; the pair limit still caps each account.</li>
 * </ul>
 *
 * <p>The window is <b>fixed</b>, not sliding: it starts at the first attempt and the count starts again at 1 with
 * the first attempt after {@link #WINDOW}. A block is audited once, on its first refused request
 * ({@code LOGIN_THROTTLED}). Keys are stored as SHA-256 hex, never the typed username. Each call commits on its own:
 * a refused login rolls nothing back.
 */
@Service
class LoginThrottling {

    static final int PAIR_MAX_ATTEMPTS = 10;
    static final int ADDRESS_MAX_FAILURES = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK = Duration.ofMinutes(15);

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
     * Before the password check: refuses a blocked address or pair, else counts the attempt for the pair.
     *
     * @param username  normalized
     * @param accountId the account with that username, or {@code null} if none (for the audit row)
     * @throws LoginThrottledException while the address or the pair is blocked
     */
    @Transactional(noRollbackFor = LoginThrottledException.class)
    public void countAttempt(String username, String clientAddress, UUID accountId) {
        Instant now = clock.instant();
        String addressKey = addressKey(clientAddress);
        Optional<Instant> addressBlock = throttles.findById(addressKey).map(LoginThrottle::getBlockedUntil)
                .filter(until -> until.isAfter(now));
        if (addressBlock.isPresent()) {
            throw refused(addressKey, addressBlock.get(), now, "ClientAddress", clientAddress, clientAddress);
        }
        String pairKey = pairKey(username, clientAddress);
        List<Integer> counted = throttles.countAttempt(pairKey, now, now.minus(WINDOW), PAIR_MAX_ATTEMPTS,
                now.plus(BLOCK));
        if (counted.isEmpty()) {
            Instant blockedUntil = throttles.findById(pairKey).map(LoginThrottle::getBlockedUntil).orElse(now);
            throw refused(pairKey, blockedUntil, now, "UserAccount",
                    accountId == null ? "unknown-username" : accountId.toString(), clientAddress);
        }
    }

    /** After a refused login (unknown username, wrong password, inactive account): counts it for the address. */
    @Transactional
    public void failed(String clientAddress) {
        Instant now = clock.instant();
        throttles.countAttempt(addressKey(clientAddress), now, now.minus(WINDOW), ADDRESS_MAX_FAILURES,
                now.plus(BLOCK));
    }

    @Transactional
    public void succeeded(String username, String clientAddress) {
        throttles.deleteById(pairKey(username, clientAddress));
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

    /** The 429; the first refusal of a block also writes the audit row (committed: no rollback for this one). */
    private LoginThrottledException refused(String key, Instant blockedUntil, Instant now, String entityType,
                                            String entityId, String clientAddress) {
        if (throttles.noticeBlock(key, now) == 1) {
            log.warn("BackOffice login throttled: {} {} from {} until {}", entityType, entityId, clientAddress,
                    blockedUntil);
            auditLog.recordAsSystem("LOGIN_THROTTLED", entityType, entityId, null, null, null,
                    Map.of("clientAddress", clientAddress, "blockedUntil", blockedUntil.toString()));
        }
        return new LoginThrottledException(Duration.between(now, blockedUntil));
    }

    private static String pairKey(String username, String clientAddress) {
        return sha256("pair:" + username + "|" + clientAddress);
    }

    private static String addressKey(String clientAddress) {
        return sha256("addr:" + clientAddress);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
