package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A POS session (D182): an employee's PIN login on one terminal of one store. The client holds an opaque 256-bit
 * token ({@code X-Pos-Session}); only its SHA-256 is kept, so the table never holds a usable credential. It ends after
 * {@link #IDLE} without use or {@link #ABSOLUTE} after the login (a shift), on logout, when the terminal is revoked or
 * the store deactivated, and when a manager resets the employee's PIN.
 */
@Entity
@Table(schema = "identity", name = "terminal_session")
public class TerminalSession {

    /** As the R2 server-side session (servlet default). */
    public static final Duration IDLE = Duration.ofMinutes(30);
    /** A shift (D175: SSO maximum 10 h). */
    public static final Duration ABSOLUTE = Duration.ofHours(10);
    /** A use refreshes the idle clock at most this often, so a busy terminal writes at most once a minute. */
    static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    @Id
    @Column(length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID employeeId;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant lastUsedAt;

    protected TerminalSession() {
        // JPA
    }

    public TerminalSession(String tokenHash, UUID terminalId, UUID storeId, UUID employeeId, Instant now) {
        this.tokenHash = tokenHash;
        this.terminalId = terminalId;
        this.storeId = storeId;
        this.employeeId = employeeId;
        this.startedAt = now;
        this.lastUsedAt = now;
    }

    /** Neither idle for {@link #IDLE} nor older than {@link #ABSOLUTE}. */
    public boolean isLiveAt(Instant now) {
        return now.isBefore(lastUsedAt.plus(IDLE)) && now.isBefore(startedAt.plus(ABSOLUTE));
    }

    /** A use now. @return whether the row changed (written at most once per {@link #TOUCH_EVERY}) */
    public boolean usedAt(Instant now) {
        if (now.isBefore(lastUsedAt.plus(TOUCH_EVERY))) {
            return false;
        }
        lastUsedAt = now;
        return true;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }
}
