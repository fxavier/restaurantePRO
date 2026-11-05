package com.xavier.backend.identity.model;

import com.xavier.backend.identity.exception.IllegalSupportGrantStateException;
import com.xavier.backend.identity.exception.SupportGrantTooLongException;
import com.xavier.backend.identity.model.dto.SupportGrantSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Temporary, authorized and audited access for a support account (spec §5, P36, D83): from {@code validFrom} for
 * at most {@link #MAX_DURATION}. ACTIVE → EXPIRED (by time, {@code SupportGrantExpiry}) or ACTIVE → REVOKED (an
 * administrator). An ended grant is never edited again. A support account logs in only while it holds a usable grant.
 */
@Entity
@Table(schema = "identity", name = "support_grant")
public class SupportGrant {

    /** P36; also the CHECK of V3011. */
    public static final Duration MAX_DURATION = Duration.ofHours(24);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private Instant validFrom;

    @Column(nullable = false, updatable = false)
    private Instant validUntil;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false)
    private UUID grantedByAccountId;

    private Instant revokedAt;

    private UUID revokedByAccountId;

    private Instant expiredAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected SupportGrant() {
        // JPA
    }

    public static SupportGrant grant(UUID accountId, Duration duration, String reason, UUID grantedBy, Instant now) {
        if (!duration.isPositive() || duration.compareTo(MAX_DURATION) > 0) {
            throw new SupportGrantTooLongException(MAX_DURATION.toMinutes());
        }
        SupportGrant grant = new SupportGrant();
        grant.accountId = accountId;
        grant.validFrom = now;
        grant.validUntil = now.plus(duration);
        grant.reason = reason;
        grant.grantedByAccountId = grantedBy;
        grant.createdAt = now;
        return grant;
    }

    /** Ends the grant early; an ended grant (revoked, or expired by the job) is a conflict. */
    public void revoke(UUID by, Instant now) {
        if (revokedAt != null || expiredAt != null) {
            throw new IllegalSupportGrantStateException();
        }
        revokedAt = now;
        revokedByAccountId = by;
    }

    /** EXPIRED as soon as {@code validUntil} passes, even before the job has set {@code expiredAt}. */
    public SupportGrantStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return SupportGrantStatus.REVOKED;
        }
        return expiredAt != null || !now.isBefore(validUntil) ? SupportGrantStatus.EXPIRED : SupportGrantStatus.ACTIVE;
    }

    public boolean isUsableAt(Instant now) {
        return statusAt(now) == SupportGrantStatus.ACTIVE && !now.isBefore(validFrom);
    }

    /** What is left of the grant at {@code now}; zero once it is not usable. */
    public Duration remainingAt(Instant now) {
        return isUsableAt(now) ? Duration.between(now, validUntil) : Duration.ZERO;
    }

    public SupportGrantSummary toSummary(Instant now) {
        return new SupportGrantSummary(id, accountId, validFrom, validUntil, reason, grantedByAccountId, revokedAt,
                revokedByAccountId, expiredAt, statusAt(now), getVersion());
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public UUID getRevokedByAccountId() {
        return revokedByAccountId;
    }

    public String getReason() {
        return reason;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
