package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A one-time enrolment code (D143): issued by an administrator's reset, valid 24 hours, handed over out of band, and
 * needed to enrol from a remote address or as a support account. Only its SHA-256 is kept. Using it, or a newer reset,
 * sets {@code used_at}; at most one open code per account (partial unique index).
 */
@Entity
@Table(schema = "identity", name = "mfa_enrolment_code")
public class MfaEnrolmentCode {

    public static final Duration VALIDITY = Duration.ofHours(24);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String codeHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private UUID issuedByAccountId;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaEnrolmentCode() {
        // JPA
    }

    public static MfaEnrolmentCode issue(UUID accountId, String codeHash, UUID issuedBy, String reason, Instant now) {
        MfaEnrolmentCode code = new MfaEnrolmentCode();
        code.accountId = accountId;
        code.codeHash = codeHash;
        code.issuedByAccountId = issuedBy;
        code.reason = reason;
        code.createdAt = now;
        code.expiresAt = now.plus(VALIDITY);
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
