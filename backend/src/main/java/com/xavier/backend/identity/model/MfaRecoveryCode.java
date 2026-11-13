package com.xavier.backend.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One of a factor's 10 recovery codes (D139), kept only as the SHA-256 of the normalized code. Using one is a
 * compare-and-set on {@code used_at} ({@code MfaRecoveryCodeRepository.consume}), so it works once.
 */
@Entity
@Table(schema = "identity", name = "mfa_recovery_code")
public class MfaRecoveryCode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID factorId;

    @Column(nullable = false, updatable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String codeHash;

    private Instant usedAt;

    protected MfaRecoveryCode() {
        // JPA
    }

    public MfaRecoveryCode(UUID factorId, String codeHash) {
        this.factorId = factorId;
        this.codeHash = codeHash;
    }
}
