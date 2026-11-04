package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A login throttle count (r2-decisions §12): the attempts of one username from one client address, or the failures
 * from one client address across usernames. {@code key} is a SHA-256 hex. Written only by the atomic statements in
 * {@code LoginThrottleRepository}, so concurrent attempts are all counted; this mapping exists for that repository.
 */
@Entity
@Table(schema = "identity", name = "login_throttle")
public class LoginThrottle {

    @Id
    @Column(length = 64)
    private String key;

    @Column(nullable = false)
    private int failures;

    @Column(nullable = false)
    private Instant windowStart;

    private Instant blockedUntil;

    @Column(nullable = false)
    private boolean blockNoticed;

    protected LoginThrottle() {
        // JPA
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }
}
