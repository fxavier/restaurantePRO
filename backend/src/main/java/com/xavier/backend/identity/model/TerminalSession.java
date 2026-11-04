package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A POS session opened on a terminal, so revoking the terminal or deactivating its store can end it. */
@Entity
@Table(schema = "identity", name = "terminal_session")
public class TerminalSession {

    @Id
    @Column(length = 36)
    private String sessionId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID employeeId;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    protected TerminalSession() {
        // JPA
    }

    public TerminalSession(String sessionId, UUID terminalId, UUID storeId, UUID employeeId, Instant startedAt) {
        this.sessionId = sessionId;
        this.terminalId = terminalId;
        this.storeId = storeId;
        this.employeeId = employeeId;
        this.startedAt = startedAt;
    }

    public String getSessionId() {
        return sessionId;
    }
}
