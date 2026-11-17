package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.TerminalSession;
import com.xavier.backend.identity.repository.TerminalSessionRepository;
import com.xavier.backend.organization.model.event.StoreDeactivated;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS sessions (D182; interim terminal identity until S73): opened by the PIN login with an opaque 256-bit token, of
 * which only the SHA-256 is stored; resolved on every POS request ({@code X-Pos-Session}); ended on logout, on a PIN
 * reset, on deactivation, when the terminal is revoked (spec §5, D26) or the store deactivated (D45). No token, PIN or
 * hash is ever logged.
 */
@Service
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TerminalSessionRepository sessions;
    private final Clock clock;

    TerminalSessionService(TerminalSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** A new session. @return its token, given to the client once */
    @Transactional
    public String open(UUID terminalId, UUID storeId, UUID employeeId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.save(new TerminalSession(hash(token), terminalId, storeId, employeeId, clock.instant()));
        return token;
    }

    /** The live session of {@code token}, its idle time restarted; an expired one is deleted. */
    @Transactional
    public Optional<TerminalSession> use(String token) {
        String hash = hash(token);
        Optional<TerminalSession> found = sessions.findById(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TerminalSession session = found.get();
        if (!session.isLiveAt(clock.instant())) {
            sessions.delete(session);
            return Optional.empty();
        }
        session.usedAt(clock.instant());
        return found;
    }

    @Transactional
    public void end(String token) {
        sessions.deleteById(hash(token));
    }

    /** Every POS session of the employee (PIN reset, deactivation). */
    @Transactional
    public void endEmployee(UUID employeeId) {
        sessions.deleteByEmployee(employeeId);
    }

    /** Idempotent: a second delivery finds nothing left to end. */
    @ApplicationModuleListener
    void on(TerminalRevoked event) {
        log.info("Terminal {} revoked: ended {} POS session(s)", event.terminalId(),
                sessions.deleteByTerminal(event.terminalId()));
    }

    /** Idempotent: a second delivery finds nothing left to end. */
    @ApplicationModuleListener
    void on(StoreDeactivated event) {
        log.info("Store {} deactivated: ended {} POS session(s)", event.storeId(),
                sessions.deleteByStore(event.storeId()));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
