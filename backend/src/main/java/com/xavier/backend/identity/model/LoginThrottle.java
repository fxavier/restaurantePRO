package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The password attempts of one username from one client address (r2-decisions §12). Written only by the atomic
 * upsert in {@code LoginThrottleRepository.countAttempt}, so concurrent attempts are all counted; this mapping
 * exists for that repository and for deletes.
 */
@Entity
@Table(schema = "identity", name = "login_throttle")
public class LoginThrottle {

    @Id
    @Column(length = 120)
    private String key;

    @Column(nullable = false)
    private int failures;

    @Column(nullable = false)
    private Instant windowStart;

    private Instant blockedUntil;

    protected LoginThrottle() {
        // JPA
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }
}
