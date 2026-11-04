package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.TerminalSession;
import com.xavier.backend.identity.repository.TerminalSessionRepository;
import com.xavier.backend.organization.model.event.StoreDeactivated;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers which sessions run on which terminal of which store, so revoking a terminal (spec §5, D26) or
 * deactivating a store (D45) ends them.
 */
@Service
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);

    private final TerminalSessionRepository terminalSessions;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final Clock clock;

    TerminalSessionService(TerminalSessionRepository terminalSessions, FindByIndexNameSessionRepository<?> sessions,
                           Clock clock) {
        this.terminalSessions = terminalSessions;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public void register(String sessionId, UUID terminalId, UUID storeId, UUID employeeId) {
        terminalSessions.save(new TerminalSession(sessionId, terminalId, storeId, employeeId, clock.instant()));
    }

    @Transactional
    public void forget(String sessionId) {
        terminalSessions.deleteById(sessionId);
    }

    /** Idempotent: a second delivery finds nothing left to end. */
    @ApplicationModuleListener
    void on(TerminalRevoked event) {
        int ended = end(terminalSessions.findByTerminalId(event.terminalId()));
        log.info("Terminal {} revoked: ended {} POS session(s)", event.terminalId(), ended);
    }

    /** Idempotent: a second delivery finds nothing left to end. */
    @ApplicationModuleListener
    void on(StoreDeactivated event) {
        int ended = end(terminalSessions.findByStoreId(event.storeId()));
        log.info("Store {} deactivated: ended {} POS session(s)", event.storeId(), ended);
    }

    private int end(List<TerminalSession> open) {
        open.forEach(session -> sessions.deleteById(session.getSessionId()));
        terminalSessions.deleteAll(open);
        return open.size();
    }
}
